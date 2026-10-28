package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.AiClient;
import com.propertyos.common.config.ConfigClient;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/** Leases: generate with state rules, e-sign, activation, renewals, notice and move-out. */
@Service
public class LeaseService {
    private static final String WF = "LEASE";
    private final Db db;
    private final StateMachine sm;
    private final WorkflowEngine wf;
    private final ConfigClient config;
    private final EventPublisher events;
    private final Notifier notifier;
    private final AiClient ai;

    public LeaseService(Db db, StateMachine sm, WorkflowEngine wf, ConfigClient config, EventPublisher events, Notifier notifier, AiClient ai) {
        this.db = db;
        this.sm = sm;
        this.wf = wf;
        this.config = config;
        this.events = events;
        this.notifier = notifier;
        this.ai = ai;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        String sql = """
                select id, unit_id, property_id, status, tenant_name, tenant_email, unit_label, property_name, rent, deposit, start_date, end_date, state,
                       (end_date - current_date) as days_left, renewal
                from leasing.lease""";
        return status == null ? db.list(sql + " order by end_date") : db.list(sql + " where status = ? order by end_date", status);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID id) {
        Map<String, Object> l = new LinkedHashMap<>(db.one("Lease", "select * from leasing.lease where id = ?", id));
        l.put("actions", wf.availableActions(WF, (String) l.get("status")));
        return l;
    }

    /** The signed-in resident's current lease. */
    @Transactional(readOnly = true)
    public Map<String, Object> mine() {
        UUID party = UserContextHolder.require().partyId();
        Map<String, Object> l = db.find("select id from leasing.lease where tenant_id = ? and status not in ('VOID','CLOSED') order by start_date desc limit 1", party)
                .orElseThrow(() -> ApiException.notFound("Lease"));
        return get((UUID) l.get("id"));
    }

    @Transactional
    public Map<String, Object> fromApplication(UUID applicationId, Map<String, Object> b) {
        Map<String, Object> a = db.one("Application", "select * from leasing.application where id = ?", applicationId);
        if (!"APPROVED".equals(a.get("status"))) throw ApiException.conflict("Only approved applications can become leases.");
        if (db.find("select id from leasing.lease where application_id = ? and status <> 'VOID'", applicationId).isPresent())
            throw ApiException.conflict("A lease already exists for this application.");
        Map<String, Object> u = db.one("Unit", """
                select u.*, p.name as pname, p.street, p.city, p.state, p.zip from leasing.unit u join leasing.property p on p.id = u.property_id where u.id = ?""", a.get("unit_id"));
        Map<String, Object> li = db.one("Listing", "select rent, deposit from leasing.listing where id = ?", a.get("listing_id"));
        LocalDate start = Optional.ofNullable(Req.date(b, "startDate")).orElse(LocalDate.parse(String.valueOf(a.get("move_in_date"))));
        int months = Optional.ofNullable(Req.integer(b, "termMonths")).orElse(12);
        BigDecimal rent = Optional.ofNullable(Req.money(b, "rent")).orElse((BigDecimal) li.get("rent"));
        BigDecimal reqDeposit = Optional.ofNullable(Req.money(b, "deposit")).orElse((BigDecimal) li.get("deposit"));
        String state = (String) u.get("state");
        JsonNode rule = config.stateRule(state);
        LeaseDocument.Result doc = LeaseDocument.generate(orgName(), (String) a.get("applicant_name"), (String) u.get("pname"),
                u.get("street") + ", " + u.get("city") + ", " + state + " " + u.get("zip"), "Unit " + u.get("number"),
                start, start.plusMonths(months).minusDays(1), rent, reqDeposit, state, rule, config.at("fees"), (String) a.get("pets"));
        UUID id = UUID.randomUUID();
        db.update("""
                insert into leasing.lease (id, org_id, unit_id, property_id, owner_id, tenant_id, application_id, status, start_date, end_date, rent, deposit, state,
                    rules_snapshot, terms, document_text, unit_label, property_name, property_address, tenant_name, tenant_email)
                values (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)""",
                id, UserContextHolder.orgId(), a.get("unit_id"), a.get("property_id"), a.get("owner_id"), a.get("applicant_id"), applicationId,
                start, start.plusMonths(months).minusDays(1), rent, doc.deposit(), state, db.json(rule), db.json(doc.terms()), doc.text(),
                "Unit " + u.get("number"), u.get("pname"), u.get("street") + ", " + u.get("city") + ", " + state, a.get("applicant_name"), a.get("applicant_email"));
        sm.created("Lease", id, "DRAFT", Map.of("adjustments", doc.adjustments()));
        Map<String, Object> out = new LinkedHashMap<>(get(id));
        out.put("adjustments", doc.adjustments());
        return out;
    }

    @Transactional
    public Map<String, Object> act(UUID id, String action, Map<String, Object> body) {
        Map<String, Object> l = db.one("Lease", "select * from leasing.lease where id = ?", id);
        String reason = Req.str(body, "reason");
        UserContext ctx = UserContextHolder.require();
        if ("TENANT_SIGN".equals(action)) {
            String sig = Req.required(body, "signature");
            if (!sig.trim().equalsIgnoreCase(String.valueOf(l.get("tenant_name")).trim()))
                throw ApiException.badRequest("Type your full legal name exactly as shown on the lease: " + l.get("tenant_name"));
            sm.apply("Lease", "leasing.lease", id, WF, action, reason, null, Map.of("signature", sig));
            db.update("update leasing.lease set tenant_signature = ?, tenant_signed_at = now() where id = ?", sig, id);
            notifier.toRole("PROPERTY_MANAGER", "Lease signed by tenant", l.get("tenant_name") + " signed the lease for " + l.get("unit_label") + ". Countersign to activate.", "/leasing/leases/" + id, "HIGH");
            return get(id);
        }
        if ("RENEWAL_ACCEPT".equals(action)) action = "ACCEPT_RENEWAL";
        StateMachine.Outcome o = sm.apply("Lease", "leasing.lease", id, WF, action, reason, null, null);
        switch (o.to()) {
            case "SENT" -> notifier.toParty((UUID) l.get("tenant_id"), "Your lease is ready to sign", "Review and sign the lease for " + l.get("unit_label") + ".", "/my-lease");
            case "ACTIVE" -> {
                if ("COUNTERSIGN".equals(action)) activate(l, ctx);
                else if ("ACCEPT_RENEWAL".equals(action)) applyRenewal(l);
            }
            case "NOTICE_GIVEN" -> db.update("update leasing.lease set notice_date = current_date, move_out_date = coalesce(?, end_date) where id = ?", Req.date(body, "moveOutDate"), id);
            case "ENDED" -> {
                db.update("update leasing.unit set status = 'VACANT', current_rent = null, vacant_since = current_date, updated_at = now() where id = ?", l.get("unit_id"));
                events.publish("LeaseEnded", "Lease", id, Map.of("unitId", l.get("unit_id").toString(), "tenantId", l.get("tenant_id").toString(),
                        "deposit", l.get("deposit").toString(), "state", l.get("state").toString()));
            }
            default -> { }
        }
        return get(id);
    }

    private void activate(Map<String, Object> l, UserContext ctx) {
        UUID id = (UUID) l.get("id");
        db.update("update leasing.lease set manager_signature = ?, manager_signed_at = now() where id = ?", ctx.name(), id);
        db.update("update leasing.unit set status = 'OCCUPIED', current_rent = ?, vacant_since = null, updated_at = now() where id = ?", l.get("rent"), l.get("unit_id"));
        db.update("update leasing.listing set status = 'LEASED', updated_at = now() where unit_id = ? and status <> 'LEASED'", l.get("unit_id"));
        Map<String, Object> payload = new HashMap<>();
        for (String k : List.of("unit_id:unitId", "tenant_id:tenantId", "rent:rent", "deposit:deposit", "property_id:propertyId", "owner_id:ownerId",
                "start_date:startDate", "tenant_name:tenantName", "unit_label:unitLabel", "property_name:propertyName", "state:state")) {
            String[] kv = k.split(":");
            payload.put(kv[1], l.get(kv[0]).toString());
        }
        events.publish("LeaseActivated", "Lease", id, payload);
    }

    /** Offers a renewal; the rent is AI-suggested from market and tenant reliability, capped by the organisation's maximum increase. */
    @Transactional
    public Map<String, Object> offerRenewal(UUID id, Map<String, Object> b) {
        Map<String, Object> l = db.one("Lease", "select l.*, u.market_rent from leasing.lease l join leasing.unit u on u.id = l.unit_id where l.id = ?", id);
        BigDecimal current = (BigDecimal) l.get("rent");
        BigDecimal market = (BigDecimal) l.get("market_rent");
        BigDecimal maxPct = config.number("renewals.maxIncreasePercent", "6");
        BigDecimal cap = current.multiply(BigDecimal.ONE.add(maxPct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))).setScale(0, RoundingMode.HALF_UP);
        BigDecimal target = market.min(cap).max(current);
        String note = "Market rent is $" + market + ". The increase is limited to " + maxPct + "% by your policy.";
        BigDecimal proposed = Optional.ofNullable(Req.money(b, "rent")).orElse(target);
        if (proposed.compareTo(cap) > 0) throw ApiException.badRequest("The increase exceeds the maximum of " + maxPct + "% ($" + cap + ").");
        int months = Optional.ofNullable(Req.integer(b, "termMonths")).orElse(12);
        Map<String, Object> renewal = new LinkedHashMap<>();
        renewal.put("rent", proposed);
        renewal.put("termMonths", months);
        renewal.put("note", note);
        renewal.put("increase", proposed.subtract(current));
        renewal.put("offeredAt", java.time.Instant.now().toString());
        sm.apply("Lease", "leasing.lease", id, WF, "OFFER_RENEWAL", null, null, Map.of("rent", proposed));
        db.update("update leasing.lease set renewal = ?::jsonb where id = ?", db.json(renewal), id);
        notifier.toParty((UUID) l.get("tenant_id"), "Renewal offer", "Your lease can be renewed at $" + proposed + " per month.", "/my-lease");
        return get(id);
    }

    private void applyRenewal(Map<String, Object> l) {
        JsonNode r = db.tree(db.one("Lease", "select renewal from leasing.lease where id = ?", l.get("id")).get("renewal"));
        if (r.isMissingNode() || r.isNull()) return;
        BigDecimal rent = new BigDecimal(r.path("rent").asText());
        int months = r.path("termMonths").asInt(12);
        LocalDate end = LocalDate.parse(l.get("end_date").toString()).plusMonths(months);
        db.update("update leasing.lease set rent = ?, end_date = ?, renewal = null where id = ?", rent, end, l.get("id"));
        db.update("update leasing.unit set current_rent = ? where id = ?", rent, l.get("unit_id"));
        events.publish("LeaseRenewed", "Lease", (UUID) l.get("id"), Map.of("rent", rent.toString()));
    }

    private String orgName() {
        return config.text("branding.companyName", "Property management company");
    }
}
