package com.propertyos.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Rule engine: when a business event occurs and its conditions match, run the configured actions.
 * Actions: NOTIFY (role, user, party or a party id taken from the event) and CALL (an internal service endpoint).
 * Every execution is recorded, and each rule carries an estimate of staff minutes saved per run.
 */
@Component
public class AutomationService implements EventHandler {
    /**
     * Internal housekeeping events that never trigger rules.
     */
    private static final Set<String> IGNORED = Set.of("NotificationRequested", "DemoResetRequested");
    /**
     * Comparison operators a rule condition may use.
     */
    private static final Set<String> OPERATORS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "contains");
    private final Db db;
    private final Notifier notifier;
    private final ServiceClient client;

    public AutomationService(Db db, Notifier notifier, ServiceClient client) {
        this.db = db;
        this.notifier = notifier;
        this.client = client;
    }

    /**
     * Name under which this handler's event position is stored.
     */
    @Override
    public String subscriber() {
        return "workflow.automation";
    }

    /**
     * Subscribes to every event type.
     */
    @Override
    public Set<String> eventTypes() {
        return Set.of("*");
    }

    /**
     * Runs each enabled rule whose trigger matches the event.
     */
    @Override
    @Transactional
    public void handle(DomainEvent e) {
        if (IGNORED.contains(e.type())) return;
        for (Map<String, Object> rule : db.list("select * from workflow.automation_rule where enabled and trigger_event = ?", e.type())) {
            if (!matches(db.tree(rule.get("conditions")), e)) continue;
            String status = "SUCCEEDED", detail = null;
            try {
                List<String> done = new ArrayList<>();
                for (JsonNode a : db.tree(rule.get("actions"))) done.add(run(a, e));
                detail = String.join("; ", done);
            } catch (Exception ex) {
                status = "FAILED";
                detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
            db.update("insert into workflow.automation_run (id, org_id, rule_id, rule_name, event_id, event_type, status, detail) values (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), e.orgId(), rule.get("id"), rule.get("name"), e.id(), e.type(), status, shorten(detail));
            db.update("update workflow.automation_rule set runs = runs + 1, last_run_at = now() where id = ?", rule.get("id"));
        }
    }

    private String run(JsonNode a, DomainEvent e) {
        String type = a.path("type").asText();
        switch (type) {
            case "NOTIFY" -> {
                String audience = a.path("audience").asText("ROLE");
                String target = a.hasNonNull("targetField") ? e.text(a.path("targetField").asText()) : a.path("target").asText();
                if (target == null || target.isBlank()) throw new IllegalStateException("No recipient found on the event");
                notifier.send(audience, target, render(a.path("title").asText("Update"), e), render(a.path("body").asText(""), e), a.path("link").asText(null),
                        a.path("priority").asText("NORMAL"));
                return "Notified " + audience.toLowerCase() + " " + (a.hasNonNull("target") ? a.path("target").asText() : "from event");
            }
            case "CALL" -> {
                ServiceClient.Service svc = ServiceClient.Service.valueOf(a.path("service").asText("LEASING").toUpperCase());
                String path = render(a.path("path").asText(), e);
                if (!path.startsWith("/internal/")) throw new IllegalStateException("Automation can only call internal endpoints");
                client.post(svc, path, Map.of());
                return "Called " + svc + " " + path;
            }
            default -> throw new IllegalStateException("Unknown action type " + type);
        }
    }

    static boolean matches(JsonNode conditions, DomainEvent e) {
        for (JsonNode c : conditions) {
            String field = c.path("field").asText();
            String actual = "aggregateId".equals(field) ? String.valueOf(e.aggregateId()) : e.text(field);
            String op = c.path("op").asText("eq"), expected = c.path("value").asText();
            if (actual == null) return false;
            boolean ok;
            try {
                ok = switch (op) {
                    case "eq" -> actual.equalsIgnoreCase(expected);
                    case "ne" -> !actual.equalsIgnoreCase(expected);
                    case "contains" -> actual.toLowerCase().contains(expected.toLowerCase());
                    case "gt" -> Double.parseDouble(actual) > Double.parseDouble(expected);
                    case "gte" -> Double.parseDouble(actual) >= Double.parseDouble(expected);
                    case "lt" -> Double.parseDouble(actual) < Double.parseDouble(expected);
                    case "lte" -> Double.parseDouble(actual) <= Double.parseDouble(expected);
                    default -> false;
                };
            } catch (NumberFormatException ex) {
                ok = false;
            }
            if (!ok) return false;
        }
        return true;
    }

    static String render(String template, DomainEvent e) {
        String out = template.replace("{aggregateId}", String.valueOf(e.aggregateId()));
        if (e.payload() != null)
            for (Iterator<String> it = e.payload().fieldNames(); it.hasNext(); ) {
                String f = it.next();
                out = out.replace("{{" + f + "}}", e.text(f) == null ? "" : e.text(f));
            }
        return out;
    }

    private static String shorten(String s) {
        return s == null ? null : s.length() > 480 ? s.substring(0, 477) + "..." : s;
    }

    // ---------- Management ----------
    @Component
    public static class Rules {
        private final Db db;

        public Rules(Db db) {
            this.db = db;
        }

        @Transactional(readOnly = true)
        public List<Map<String, Object>> list() {
            return db.list("select * from workflow.automation_rule order by enabled desc, name");
        }

        @Transactional(readOnly = true)
        public Map<String, Object> get(UUID id) {
            Map<String, Object> r = new LinkedHashMap<>(db.one("Rule", "select * from workflow.automation_rule where id = ?", id));
            r.put("recentRuns", db.list("select * from workflow.automation_run where rule_id = ? order by created_at desc limit 20", id));
            return r;
        }

        @Transactional
        public Map<String, Object> save(UUID id, Map<String, Object> b) {
            String trigger = Req.required(b, "triggerEvent");
            Object actions = b.get("actions");
            if (!(actions instanceof List<?> al) || al.isEmpty()) throw ApiException.badRequest("Add at least one action.");
            for (Object c : b.get("conditions") instanceof List<?> cl ? cl : List.of())
                if (c instanceof Map<?, ?> m && !OPERATORS.contains(String.valueOf(m.get("op")))) throw ApiException.badRequest("Unknown condition operator " + m.get("op") + ".");
            boolean create = id == null;
            UUID rid = create ? UUID.randomUUID() : id;
            if (create)
                db.update("insert into workflow.automation_rule (id, org_id, name, description, trigger_event, conditions, actions, minutes_saved, enabled, created_by) values (?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)",
                        rid, UserContextHolder.orgId(), Req.required(b, "name"), Req.str(b, "description"), trigger, db.json(b.getOrDefault("conditions", List.of())), db.json(actions),
                        Optional.ofNullable(Req.integer(b, "minutesSaved")).orElse(5), Req.bool(b, "enabled"), UserContextHolder.require().name());
            else if (db.update("update workflow.automation_rule set name = ?, description = ?, trigger_event = ?, conditions = ?::jsonb, actions = ?::jsonb, minutes_saved = ?, updated_at = now() where id = ?",
                    Req.required(b, "name"), Req.str(b, "description"), trigger, db.json(b.getOrDefault("conditions", List.of())), db.json(actions),
                    Optional.ofNullable(Req.integer(b, "minutesSaved")).orElse(5), rid) == 0) throw ApiException.notFound("Rule");
            return get(rid);
        }

        @Transactional
        public Map<String, Object> toggle(UUID id, boolean on) {
            if (db.update("update workflow.automation_rule set enabled = ?, updated_at = now() where id = ?", on, id) == 0) throw ApiException.notFound("Rule");
            return get(id);
        }

        @Transactional
        public void delete(UUID id) {
            db.update("delete from workflow.automation_rule where id = ?", id);
        }

        @Transactional(readOnly = true)
        public Map<String, Object> summary() {
            Map<String, Object> s = new LinkedHashMap<>(db.one("Summary", """
                    select (select count(*) from workflow.automation_rule where enabled) as active_rules,
                           (select count(*) from workflow.automation_rule) as total_rules,
                           (select count(*) from workflow.automation_run where created_at > now() - interval '30 days' and status = 'SUCCEEDED') as runs_30d,
                           (select count(*) from workflow.automation_run where created_at > now() - interval '30 days' and status = 'FAILED') as failed_30d,
                           coalesce((select sum(r.minutes_saved) from workflow.automation_run ar join workflow.automation_rule r on r.id = ar.rule_id
                                     where ar.created_at > now() - interval '30 days' and ar.status = 'SUCCEEDED'), 0) as minutes_saved_30d"""));
            s.put("hours_saved_30d", Math.round(((Number) s.get("minutes_saved_30d")).doubleValue() / 6.0) / 10.0);
            s.put("recent", db.list("select * from workflow.automation_run order by created_at desc limit 15"));
            return s;
        }

        @Transactional(readOnly = true)
        public List<Map<String, Object>> events() {
            return List.of("LeadCreated", "ShowingRequested", "ApplicationSubmitted", "ApplicationDecided", "ListingPublished", "LeaseActivated", "LeaseEnded",
                    "PaymentReceived", "PaymentFailed", "LateFeeCharged", "StatementPublished", "WorkOrderCreated", "WorkOrderAssigned", "WorkOrderApproved",
                    "WorkOrderCompleted", "SlaBreached").stream().map(e -> Map.<String, Object>of("key", e)).toList();
        }
    }
}
