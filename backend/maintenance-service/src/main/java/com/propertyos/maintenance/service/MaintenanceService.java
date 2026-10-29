package com.propertyos.maintenance.service;

import org.springframework.web.client.RestClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.AiClient;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import com.propertyos.common.workflow.StateMachine;
import com.propertyos.common.workflow.WorkflowEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** Work orders: intake, AI triage, owner approval limits, vendor matching, SLA and completion. */
@Service
public class MaintenanceService {
    /**
     * Workflow key for work orders.
     */
    static final String WF = "WORK_ORDER";
    private final Db db;
    private final StateMachine sm;
    private final WorkflowEngine wf;
    private final ConfigClient config;
    private final ServiceClient client;
    private final EventPublisher events;
    private final Notifier notifier;
    private final AiClient ai;
    private final AuditService audit;

    public MaintenanceService(Db db, StateMachine sm, WorkflowEngine wf, ConfigClient config, ServiceClient client, EventPublisher events,
                              Notifier notifier, AiClient ai, AuditService audit) {
        this.db = db;
        this.sm = sm;
        this.wf = wf;
        this.config = config;
        this.client = client;
        this.events = events;
        this.notifier = notifier;
        this.ai = ai;
        this.audit = audit;
    }

    // ---------- Reads ----------
    /**
     * Lists work orders with hours left on their SLA.
     *
     * @param status   status filter, or null
     * @param priority priority filter, or null
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String priority) {
        StringBuilder sql = new StringBuilder("""
                select w.*, case when w.status in ('CLOSED','REJECTED') then null else extract(epoch from (w.sla_due_at - now())) / 3600 end as sla_hours_left
                from maintenance.work_order w where 1=1""");
        List<Object> a = new ArrayList<>();
        if (status != null) {
            if ("ACTIVE".equals(status)) sql.append(" and w.status not in ('CLOSED','REJECTED')");
            else { sql.append(" and w.status = ?"); a.add(status); }
        }
        if (priority != null) { sql.append(" and w.priority = ?"); a.add(priority); }
        sql.append(" order by case w.priority when 'EMERGENCY' then 0 when 'HIGH' then 1 when 'NORMAL' then 2 else 3 end, w.created_at desc limit 300");
        return db.list(sql.toString(), a.toArray());
    }

    /**
     * Returns a work order with its allowed actions and SLA.
     *
     * @throws ApiException (404) when not found
     */
    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID id) {
        Map<String, Object> w = new LinkedHashMap<>(db.one("Work order", """
                select w.*, extract(epoch from (w.sla_due_at - now())) / 3600 as sla_hours_left from maintenance.work_order w where id = ?""", id));
        w.put("actions", wf.availableActions(WF, (String) w.get("status")));
        w.put("comments", db.list("select * from maintenance.work_order_comment where work_order_id = ? order by created_at", id));
        w.put("timeline", audit.timeline(id));
        if (List.of("TRIAGED").contains(w.get("status"))) w.put("vendorOptions", rank((String) w.get("category"), (UUID) w.get("property_id")));
        return w;
    }

    // ---------- Intake and triage ----------
    /**
     * Creates a work order from a staff or resident request, classifying it with {@link TriageEngine} when category or priority is missing.
     *
     * @param b title, description and location details
     * @return the new work order
     */
    @Transactional
    public Map<String, Object> create(Map<String, Object> b) {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> ref;
        UUID tenantId = null;
        if ("TENANT".equals(ctx.scope())) {
            tenantId = ctx.partyId();
            ref = leaseOf(tenantId);
        } else if (Req.uuid(b, "tenantId") != null) {
            tenantId = Req.uuid(b, "tenantId");
            ref = leaseOf(tenantId);
        } else {
            UUID propertyId = Req.requiredUuid(b, "propertyId");
            JsonNode lease = client.get(ServiceClient.Service.LEASING, "/internal/leasing/active-leases");
            ref = null;
            for (JsonNode n : lease)
                if (n.path("property_id").asText().equals(propertyId.toString())) { ref = jsonMap(n); break; }
            if (ref == null) throw ApiException.notFound("An occupied unit at that property");
        }
        String title = Req.required(b, "title");
        String description = Req.str(b, "description");
        UUID id = UUID.randomUUID();
        db.update("""
                insert into maintenance.work_order (id, org_id, property_id, owner_id, unit_id, tenant_id, property_name, unit_label, tenant_name, title, description,
                    permission_to_enter, source, photos, created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)""",
                id, UserContextHolder.orgId(), ref.get("property_id"), ref.get("owner_id"), ref.get("unit_id"), tenantId, ref.get("property_name"), ref.get("unit_label"),
                ref.get("tenant_name"), title, description, b.get("permissionToEnter") == null || Req.bool(b, "permissionToEnter"),
                "TENANT".equals(ctx.scope()) ? "TENANT" : "STAFF", db.json(Req.strings(b, "photos")), ctx.name());
        sm.created("WorkOrder", id, "SUBMITTED", Map.of("title", title));
        events.publish("WorkOrderCreated", "WorkOrder", id, Map.of("title", title));
        triage(id);
        return get(id);
    }

    /** AI triage (category, priority, cost estimate, safety), then approval routing or automatic vendor assignment. */
    @Transactional
    public void triage(UUID id) {
        Map<String, Object> w = db.one("Work order", "select * from maintenance.work_order where id = ?", id);
        String title = (String) w.get("title");
        String desc = (String) w.get("description");
        TriageEngine.Result rules = TriageEngine.classify(title, desc);
        Map<String, Object> triage = new LinkedHashMap<>();
        String category = rules.category(), priority = rules.priority();
        BigDecimal estimate = rules.estimate();
        String summary = rules.summary();
        List<String> tips = new ArrayList<>(rules.tips());
        String source = "Rules";
        Optional<JsonNode> out = ai.call("triage", Map.of("title", title, "description", desc == null ? "" : desc, "property", w.get("property_name")));
        if (out.isPresent()) {
            JsonNode n = out.get();
            category = n.path("category").asText(category);
            String p = n.path("priority").asText(priority);
            priority = rank(p) > rank(priority) ? priority : p;   // never downgrade a rule-detected emergency
            if (n.hasNonNull("estimate")) estimate = new BigDecimal(n.path("estimate").asText());
            summary = n.path("summary").asText(summary);
            source = "AI";
        }
        triage.put("source", source);
        triage.put("summary", summary);
        triage.put("safetyRisk", rules.safetyRisk());
        triage.put("tips", tips);
        triage.put("confidence", "AI".equals(source) ? 0.86 : 0.7);
        triage.put("guardrail", "Emergencies are never downgraded by AI. Cost above the owner's limit always needs owner approval.");
        OffsetDateTime sla = OffsetDateTime.now().plusHours(TriageEngine.slaHours(priority));
        db.update("update maintenance.work_order set category = ?, priority = ?, estimate = ?, ai_triage = ?::jsonb, sla_due_at = ? where id = ?",
                category, priority, estimate, db.json(triage), sla, id);
        sm.applyAsSystem("WorkOrder", "maintenance.work_order", id, WF, "TRIAGE", "Triaged: " + category + " / " + priority, Map.of("estimate", estimate));
        route(id, category, priority, estimate, (UUID) w.get("owner_id"), (UUID) w.get("property_id"));
    }

    private int rank(String p) {
        return switch (p) {
            case "EMERGENCY" -> 0;
            case "HIGH" -> 1;
            case "NORMAL" -> 2;
            default -> 3;
        };
    }

    private void route(UUID id, String category, String priority, BigDecimal estimate, UUID ownerId, UUID propertyId) {
        BigDecimal limit = ownerLimit(ownerId);
        boolean emergency = "EMERGENCY".equals(priority);
        if (!emergency && estimate.compareTo(limit) > 0) {
            sm.applyAsSystem("WorkOrder", "maintenance.work_order", id, WF, "REQUEST_APPROVAL", "Estimate $" + estimate + " is above the owner's $" + limit + " limit", Map.of("limit", limit));
            notifier.toParty(ownerId, "Repair needs your approval", "Estimated $" + estimate + " for a " + category.toLowerCase() + " repair.", "/maintenance/" + id);
            return;
        }
        if (emergency || config.flag("automation.autoAssignVendor", true))
            UserContextHolder.runAs(UserContext.system(UserContextHolder.orgId()), () -> autoAssign(id, category, propertyId, emergency));
    }

    private void autoAssign(UUID id, String category, UUID propertyId, boolean emergency) {
        // Vendors are not visible to residents under row-level security, so rank in a separate system transaction.
        var requiresNew = new org.springframework.transaction.support.TransactionTemplate(tm);
        requiresNew.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<Map<String, Object>> options = UserContextHolder.runAs(UserContext.system(UserContextHolder.orgId()), () -> requiresNew.execute(st -> rank(category, propertyId)));
        if (options.isEmpty()) {
            notifier.toRole("PROPERTY_MANAGER", "No vendor available", "Assign a vendor manually for work order " + id.toString().substring(0, 8) + ".", "/maintenance/" + id, "HIGH");
            return;
        }
        Map<String, Object> top = options.get(0);
        assignInternal(id, (UUID) top.get("id"), (String) top.get("name"), true, emergency ? "Emergency: top-ranked vendor dispatched immediately" : "Auto-assigned to the best-ranked vendor");
    }

    private BigDecimal ownerLimit(UUID ownerId) {
        try {
            JsonNode o = client.get(ServiceClient.Service.LEASING, "/internal/leasing/owners/" + ownerId);
            return new BigDecimal(o.path("approval_limit").asText());
        } catch (RestClientException | NumberFormatException e) {
            return config.number("approvals.maintenanceOwnerLimit", "500");
        }
    }

    // ---------- Vendors ----------
    /** Ranks eligible vendors: right trade, serves the city, insured; scored on rating, response speed and cost. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> rank(String category, UUID propertyId) {
        String city = cityOf(propertyId);
        List<Map<String, Object>> vendors = db.list("""
                select v.*, (select count(*) from maintenance.work_order w where w.vendor_id = v.id and w.status in ('ASSIGNED','SCHEDULED','IN_PROGRESS')) as open_jobs
                from maintenance.vendor v where v.active and (insurance_expires is null or insurance_expires > current_date)""");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : vendors) {
            JsonNode trades = db.tree(v.get("trades")), cities = db.tree(v.get("cities"));
            boolean trade = false, serves = city == null;
            for (JsonNode t : trades) if (t.asText().equals(category) || "GENERAL".equals(category) && t.asText().equals("GENERAL")) trade = true;
            for (JsonNode c : cities) if (city != null && c.asText().equalsIgnoreCase(city)) serves = true;
            if (!trade || !serves) continue;
            double rating = ((BigDecimal) v.get("rating")).doubleValue();
            double resp = ((BigDecimal) v.get("avg_response_hours")).doubleValue();
            double cost = ((BigDecimal) v.get("avg_job_cost")).doubleValue();
            int open = ((Number) v.get("open_jobs")).intValue();
            double score = rating * 16 + Math.max(0, 24 - resp) * 1.2 + Math.max(0, 600 - cost) / 30 - open * 4;
            Map<String, Object> m = new LinkedHashMap<>(v);
            m.put("score", Math.round(score * 10) / 10.0);
            m.put("why", String.format("%.1f rating, ~%.0fh response, avg job $%.0f, %d open job(s)", rating, resp, cost, open));
            out.add(m);
        }
        out.sort((x, y) -> Double.compare((Double) y.get("score"), (Double) x.get("score")));
        return out;
    }

    private String cityOf(UUID propertyId) {
        try {
            JsonNode n = client.get(ServiceClient.Service.LEASING, "/internal/leasing/property/" + propertyId);
            return n.path("city").asText(null);
        } catch (RestClientException e) {
            return null;
        }
    }

    /**
     * Lists vendors with open job counts and recent ratings.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> vendors() {
        return db.list("""
                select v.*, (select count(*) from maintenance.work_order w where w.vendor_id = v.id and w.status in ('ASSIGNED','SCHEDULED','IN_PROGRESS')) as open_jobs,
                       (select round(avg(rating), 2) from maintenance.work_order w where w.vendor_id = v.id and w.rating is not null) as recent_rating
                from maintenance.vendor v order by v.name""");
    }

    // ---------- Actions ----------
    /**
     * Applies a workflow action to a work order and performs its side effects, for example notifying the resident.
     *
     * @param id     the work order
     * @param action the workflow action
     * @param body  reason and any action-specific fields
     */
    @Transactional
    public Map<String, Object> act(UUID id, String action, Map<String, Object> body) {
        Map<String, Object> w = db.one("Work order", "select * from maintenance.work_order where id = ?", id);
        String reason = Req.str(body, "reason");
        switch (action) {
            case "ASSIGN" -> {
                UUID vendorId = Req.requiredUuid(body, "vendorId");
                Map<String, Object> v = db.one("Vendor", "select id, name from maintenance.vendor where id = ?", vendorId);
                assignInternal(id, vendorId, (String) v.get("name"), false, reason);
            }
            case "APPROVE" -> {
                BigDecimal amount = Optional.ofNullable(Req.money(body, "amount")).orElse((BigDecimal) w.get("estimate"));
                sm.apply("WorkOrder", "maintenance.work_order", id, WF, "APPROVE", reason, amount, Map.of("approvedAmount", amount));
                notifier.toRole("PROPERTY_MANAGER", "Owner approved a repair", w.get("title") + " was approved up to $" + amount + ".", "/maintenance/" + id, "NORMAL");
                events.publish("WorkOrderApproved", "WorkOrder", id, Map.of("amount", amount.toString()));
                if (config.flag("automation.autoAssignVendor", true)) {
                    UserContext sys = UserContext.system(UserContextHolder.orgId());
                    UserContextHolder.runAs(sys, () -> autoAssign(id, (String) w.get("category"), (UUID) w.get("property_id"), false));
                }
            }
            case "SCHEDULE" -> {
                OffsetDateTime when = OffsetDateTime.parse(Req.required(body, "scheduledFor"));
                if (when.isBefore(OffsetDateTime.now().minusMinutes(5))) throw ApiException.badRequest("Choose a time in the future.");
                sm.apply("WorkOrder", "maintenance.work_order", id, WF, "SCHEDULE", reason, null, Map.of("scheduledFor", when.toString()));
                db.update("update maintenance.work_order set scheduled_for = ? where id = ?", when, id);
                if (w.get("tenant_id") != null)
                    notifier.toParty((UUID) w.get("tenant_id"), "Repair visit scheduled", w.get("vendor_name") + " will visit on " + when.toLocalDate() + ".", "/maintenance/" + id);
            }
            case "COMPLETE" -> {
                BigDecimal cost = Req.requiredMoney(body, "cost");
                BigDecimal est = (BigDecimal) w.get("estimate");
                sm.apply("WorkOrder", "maintenance.work_order", id, WF, "COMPLETE", reason, null, Map.of("cost", cost));
                db.update("update maintenance.work_order set final_cost = ?, completion_notes = ?, completed_at = now() where id = ?", cost, Req.str(body, "notes"), id);
                if (est != null && cost.compareTo(est.multiply(new BigDecimal("1.25"))) > 0)
                    notifier.toRole("PROPERTY_MANAGER", "Final cost is over estimate", w.get("title") + " came in at $" + cost + " against an estimate of $" + est + ". Review before closing.", "/maintenance/" + id, "HIGH");
                Map<String, Object> ev = new HashMap<>();
                ev.put("cost", cost.toString());
                ev.put("ownerId", w.get("owner_id").toString());
                ev.put("propertyId", w.get("property_id").toString());
                ev.put("title", w.get("title").toString());
                events.publish("WorkOrderCompleted", "WorkOrder", id, ev);
                if (w.get("tenant_id") != null)
                    notifier.toParty((UUID) w.get("tenant_id"), "Repair completed", "Please confirm the issue is fixed.", "/maintenance/" + id);
            }
            case "VERIFY" -> {
                sm.apply("WorkOrder", "maintenance.work_order", id, WF, "VERIFY", reason, null, null);
                Integer rating = Req.integer(body, "rating");
                if (rating != null) {
                    if (rating < 1 || rating > 5) throw ApiException.badRequest("Rating must be between 1 and 5.");
                    db.update("update maintenance.work_order set rating = ?, rating_comment = ? where id = ?", rating, Req.str(body, "comment"), id);
                    if (w.get("vendor_id") != null)
                        db.update("""
                                update maintenance.vendor set jobs_completed = jobs_completed + 1,
                                  rating = round(((rating * jobs_completed) + ?) / (jobs_completed + 1), 2) where id = ?""", rating, w.get("vendor_id"));
                }
            }
            default -> sm.apply("WorkOrder", "maintenance.work_order", id, WF, action, reason, null, null);
        }
        return get(id);
    }

    private void assignInternal(UUID id, UUID vendorId, String vendorName, boolean system, String reason) {
        if (system) sm.applyAsSystem("WorkOrder", "maintenance.work_order", id, WF, "ASSIGN", reason, Map.of("vendor", vendorName));
        else sm.apply("WorkOrder", "maintenance.work_order", id, WF, "ASSIGN", reason, null, Map.of("vendor", vendorName));
        db.update("update maintenance.work_order set vendor_id = ?, vendor_name = ? where id = ?", vendorId, vendorName, id);
        events.publish("WorkOrderAssigned", "WorkOrder", id, Map.of("vendorId", vendorId.toString(), "vendor", vendorName));
        notifier.toParty(vendorId, "New job assigned", "A new work order is waiting for you.", "/maintenance/" + id);
    }

    /**
     * Adds a comment to a work order.
     *
     * @param id       the work order
     * @param body     the message
     * @param internal true to hide it from the resident
     * @throws ApiException (400) when the message is blank
     */
    @Transactional
    public Map<String, Object> comment(UUID id, String body, boolean internal) {
        Map<String, Object> w = db.one("Work order", "select * from maintenance.work_order where id = ?", id);
        UserContext ctx = UserContextHolder.require();
        if (body == null || body.isBlank()) throw ApiException.badRequest("Write a message first.");
        // Only staff can write notes that residents, owners and vendors cannot see; anyone else's comment is always visible.
        boolean staff = List.of(UserContext.SCOPE_ORG, UserContext.SCOPE_PROPERTY, UserContext.SCOPE_PLATFORM).contains(ctx.scope());
        internal = internal && staff;
        db.update("insert into maintenance.work_order_comment (id, org_id, work_order_id, property_id, owner_id, tenant_id, vendor_id, author, body, internal) values (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), ctx.orgId(), id, w.get("property_id"), w.get("owner_id"), w.get("tenant_id"), w.get("vendor_id"), ctx.name(), body, internal);
        return get(id);
    }

    // ---------- Stats and SLA ----------
    /**
     * Returns open orders, emergencies, SLA breaches and related figures.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>(db.one("Stats", """
                select count(*) filter (where status not in ('CLOSED','REJECTED')) as open_orders,
                       count(*) filter (where priority = 'EMERGENCY' and status not in ('CLOSED','REJECTED')) as open_emergencies,
                       count(*) filter (where status = 'AWAITING_APPROVAL') as awaiting_approval,
                       count(*) filter (where sla_breached) as sla_breached,
                       count(*) filter (where status not in ('CLOSED','REJECTED') and sla_due_at < now()) as overdue,
                       coalesce(sum(final_cost) filter (where completed_at > date_trunc('month', now())), 0) as cost_this_month,
                       coalesce(round(avg(extract(epoch from (completed_at - created_at)) / 3600) filter (where completed_at is not null), 1), 0) as avg_resolution_hours,
                       count(*) filter (where source = 'TENANT' and ai_triage->>'source' is not null and status not in ('SUBMITTED')) as auto_triaged
                from maintenance.work_order"""));
        s.put("by_category", db.list("select category, count(*) as count, coalesce(sum(final_cost), 0) as cost from maintenance.work_order group by category order by count(*) desc"));
        s.put("by_property", db.list("select property_name, count(*) as count from maintenance.work_order group by property_name order by count(*) desc limit 6"));
        return s;
    }

    /** Escalates work orders that miss their response target. Runs every 15 minutes and is safe to repeat. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 900000, initialDelay = 60000)
    public void checkSla() {
        for (UUID org : orgsWithOrders()) {
            UserContextHolder.runAsSystem(org, () -> {
                new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(st -> escalate());
                return null;
            });
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager tm;

    private List<UUID> orgsWithOrders() {
        return UserContextHolder.runAsSystem(null, () -> new org.springframework.transaction.support.TransactionTemplate(tm).execute(st ->
                db.list("select distinct org_id from maintenance.work_order").stream().map(r -> (UUID) r.get("org_id")).toList()));
    }

    /**
     * Marks open work orders past their SLA as breached and notifies managers.
     *
     * @return how many were escalated
     */
    @Transactional
    public int escalate() {
        List<Map<String, Object>> late = db.list("select id, title, priority, property_name from maintenance.work_order where status not in ('CLOSED','REJECTED','COMPLETED') and sla_due_at < now() and not sla_breached");
        for (Map<String, Object> w : late) {
            db.update("update maintenance.work_order set sla_breached = true where id = ?", w.get("id"));
            audit.record("WorkOrder", (UUID) w.get("id"), "SLA_BREACHED", null, null, Map.of("priority", w.get("priority")));
            notifier.toRole("PROPERTY_MANAGER", "Response target missed", w.get("title") + " at " + w.get("property_name") + " is past its target.", "/maintenance/" + w.get("id"), "HIGH");
            events.publish("SlaBreached", "WorkOrder", (UUID) w.get("id"), Map.of("priority", w.get("priority").toString()));
        }
        return late.size();
    }

    // ---------- Recurring maintenance ----------
    /**
     * Lists recurring tasks, soonest due first.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recurring() {
        return db.list("select * from maintenance.recurring_task order by next_due");
    }

    /**
     * Adds a recurring task for a property.
     *
     * @param b propertyId, title, schedule and first due date
     */
    @Transactional
    public Map<String, Object> addRecurring(Map<String, Object> b) {
        UUID propertyId = Req.requiredUuid(b, "propertyId");
        Map<String, Object> ref = propertyFacts(propertyId);
        UUID id = UUID.randomUUID();
        db.update("insert into maintenance.recurring_task (id, org_id, property_id, owner_id, property_name, title, category, every_months, next_due) values (?,?,?,?,?,?,?,?,?)",
                id, UserContextHolder.orgId(), propertyId, ref.get("owner_id"), ref.get("property_name"), Req.required(b, "title"),
                Optional.ofNullable(Req.str(b, "category")).orElse("GENERAL"), Optional.ofNullable(Req.integer(b, "everyMonths")).orElse(6),
                Optional.ofNullable(Req.date(b, "nextDue")).orElse(LocalDate.now().plusMonths(1)));
        return Map.of("id", id);
    }

    /** Creates work orders for every recurring task that is due. */
    @Transactional
    public Map<String, Object> runRecurring() {
        int created = 0;
        for (Map<String, Object> t : db.list("select * from maintenance.recurring_task where active and next_due <= current_date")) {
            UUID id = UUID.randomUUID();
            db.update("""
                    insert into maintenance.work_order (id, org_id, property_id, owner_id, property_name, title, description, category, priority, source, created_by)
                    values (?,?,?,?,?,?,?,?,'LOW','RECURRING','System')""", id, t.get("org_id"), t.get("property_id"), t.get("owner_id"), t.get("property_name"),
                    t.get("title"), "Scheduled maintenance for " + t.get("property_name"), t.get("category"));
            sm.created("WorkOrder", id, "SUBMITTED", Map.of("recurring", true));
            triage(id);
            db.update("update maintenance.recurring_task set last_run = current_date, next_due = next_due + (every_months * interval '1 month') where id = ?", t.get("id"));
            created++;
        }
        return Map.of("created", created);
    }

    // ---------- helpers ----------
    private Map<String, Object> leaseOf(UUID tenantId) {
        try {
            return jsonMap(client.get(ServiceClient.Service.LEASING, "/internal/leasing/tenant-lease/" + tenantId));
        } catch (Exception e) {
            throw ApiException.notFound("An active lease for this resident");
        }
    }

    /** Owner and name of a property, from the leasing service. Works for a property that has no lease yet. */
    private Map<String, Object> propertyFacts(UUID propertyId) {
        try {
            JsonNode n = client.get(ServiceClient.Service.LEASING, "/internal/leasing/property/" + propertyId);
            Map<String, Object> m = new HashMap<>();
            m.put("owner_id", UUID.fromString(n.path("owner_id").asText()));
            m.put("property_name", n.path("name").asText(null));
            return m;
        } catch (RestClientException e) {
            throw ApiException.notFound("Property");
        }
    }


    private Map<String, Object> jsonMap(JsonNode n) {
        Map<String, Object> m = new HashMap<>();
        for (String k : List.of("unit_id", "property_id", "owner_id", "tenant_id")) if (n.hasNonNull(k)) m.put(k, UUID.fromString(n.path(k).asText()));
        for (String k : List.of("unit_label", "property_name", "tenant_name")) m.put(k, n.path(k).asText(null));
        return m;
    }

    /**
     * Adds a vendor to the company's directory.
     *
     * @param b name and at least one trade (required); contact, phone, email, cities, insuranceExpires (optional)
     */
    @Transactional
    public Map<String, Object> createVendor(Map<String, Object> b) {
        List<String> trades = Req.strings(b, "trades");
        if (trades.isEmpty()) throw ApiException.badRequest("Choose at least one trade");
        UUID id = UUID.randomUUID();
        db.update("""
                insert into maintenance.vendor (id, org_id, name, contact, phone, email, trades, cities, insurance_expires)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)""",
                id, UserContextHolder.orgId(), Req.required(b, "name"), Req.str(b, "contact"), Req.str(b, "phone"), Req.email(b, "email"),
                db.json(trades), db.json(Req.strings(b, "cities")), Req.date(b, "insuranceExpires"));
        audit.record("Vendor", id, "CREATED", null, "ACTIVE", Map.of("name", Req.required(b, "name")));
        return Map.of("id", id);
    }

    /** Changes a vendor's details or switches them off. Only the fields sent are changed. */
    @Transactional
    public void updateVendor(UUID id, Map<String, Object> b) {
        String active = Req.str(b, "active"), email = Req.str(b, "email");
        if ("false".equalsIgnoreCase(active)) {
            Long open = db.value(Long.class, "select count(*) from maintenance.work_order where vendor_id = ? and status in ('ASSIGNED','SCHEDULED','IN_PROGRESS')", id);
            if (open != null && open > 0) throw ApiException.conflict("This vendor has " + open + (open == 1 ? " open job" : " open jobs") + ". Reassign or finish them before archiving.");
        }
        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw ApiException.badRequest("Enter a valid email address");
        if (db.update("""
                update maintenance.vendor set name = coalesce(?, name), contact = coalesce(?, contact), phone = coalesce(?, phone), email = coalesce(?, email),
                       trades = coalesce(?::jsonb, trades), cities = coalesce(?::jsonb, cities), insurance_expires = coalesce(?, insurance_expires),
                       active = coalesce(?, active) where id = ?""",
                Req.str(b, "name"), Req.str(b, "contact"), Req.str(b, "phone"), email,
                b.containsKey("trades") ? db.json(Req.strings(b, "trades")) : null, b.containsKey("cities") ? db.json(Req.strings(b, "cities")) : null,
                Req.date(b, "insuranceExpires"), active == null ? null : Boolean.valueOf(active), id) == 0)
            throw ApiException.notFound("Vendor");
        audit.record("Vendor", id, active == null ? "UPDATED" : Boolean.parseBoolean(active) ? "RESTORED" : "ARCHIVED", null, null, Map.of("fields", b.keySet().toString()));
    }
}
