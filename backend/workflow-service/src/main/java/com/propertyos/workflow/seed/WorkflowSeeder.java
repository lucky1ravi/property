package com.propertyos.workflow.seed;

import com.propertyos.common.demo.DemoData;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** Seeds a library of ready-made automation rules; the high-value ones start enabled. */
@Component
public class WorkflowSeeder implements EventHandler {
    private final Db db;
    private final TransactionTemplate tx;
    private final boolean demo;

    public WorkflowSeeder(Db db, PlatformTransactionManager tm, @Value("${propertyos.demo.enabled:true}") boolean demo) {
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.demo = demo;
    }

    /**
     * On startup in demo mode, creates sample automation rules and conversations when the database is empty.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        if (!demo) return;
        for (DemoData.Org org : List.of(DemoData.SUMMIT, DemoData.HARBOR)) {
            UserContextHolder.runAsSystem(org.id(), () -> {
                tx.executeWithoutResult(s -> {
                    if (db.value(Long.class, "select count(*) from workflow.automation_rule") == 0) seed(org.id());
                });
                return null;
            });
        }
    }

    /**
     * Name under which this handler's event position is stored.
     */
    @Override
    public String subscriber() {
        return "workflow.demo-reset";
    }

    /**
     * Event types this handler wants.
     */
    @Override
    public Set<String> eventTypes() {
        return Set.of("DemoResetRequested");
    }

    /**
     * Clears the demo company's workflow data when a demo reset is requested.
     */
    @Override
    public void handle(DomainEvent event) {
        UserContextHolder.runAsSystem(event.orgId(), () -> {
            tx.executeWithoutResult(s -> {
                for (String t : List.of("message", "conversation_member", "conversation", "notification_pref", "notification_dedupe", "automation_run", "automation_rule", "workflow_def_history", "workflow_def", "delivery", "notification"))
                    db.update("delete from workflow." + t + " where org_id = ?", event.orgId());
                seed(event.orgId());
            });
            return null;
        });
    }

    private void rule(UUID org, String name, String desc, String trigger, List<Map<String, Object>> conditions, List<Map<String, Object>> actions, int minutes, boolean on) {
        db.update("insert into workflow.automation_rule (id, org_id, name, description, trigger_event, conditions, actions, minutes_saved, enabled, created_by) values (?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)",
                UUID.randomUUID(), org, name, desc, trigger, db.json(conditions), db.json(actions), minutes, on, "PropertyOS templates");
    }

    private static Map<String, Object> notify(String audience, String target, String title, String body, String link, String priority) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "NOTIFY");
        m.put("audience", audience);
        m.put("target", target);
        m.put("title", title);
        m.put("body", body);
        m.put("link", link);
        m.put("priority", priority);
        return m;
    }

    private static Map<String, Object> notifyField(String field, String title, String body, String link) {
        Map<String, Object> m = notify("PARTY", null, title, body, link, "NORMAL");
        m.remove("target");
        m.put("targetField", field);
        return m;
    }

    private static Map<String, Object> cond(String field, String op, Object value) {
        return Map.of("field", field, "op", op, "value", value);
    }

    private void seed(UUID org) {
        rule(org, "Screen every new application automatically", "Runs the credit, income and background check the moment an application is submitted, so the file is ready to decide.",
                "ApplicationSubmitted", List.of(), List.of(Map.of("type", "CALL", "service", "LEASING", "path", "/internal/leasing/applications/{aggregateId}/screen")), 20, true);
        rule(org, "Alert agents to hot leads", "Notifies the leasing team when an enquiry scores 70 or higher so they can respond first.",
                "LeadCreated", List.of(cond("score", "gte", 70)), List.of(notify("ROLE", "LEASING_AGENT", "Hot lead: {{name}}", "Scored {{score}} out of 100. Reply now.", "/leasing/leads", "HIGH")), 8, true);
        rule(org, "Tell managers about new tour requests", "Notifies the property manager whenever a showing is requested.",
                "ShowingRequested", List.of(), List.of(notify("ROLE", "PROPERTY_MANAGER", "New showing request", "A prospect asked to tour at {{at}}.", "/leasing/showings", "NORMAL")), 4, true);
        rule(org, "Flag failed payments to accounting", "Alerts the accountant when a resident's payment is declined.",
                "PaymentFailed", List.of(), List.of(notify("ROLE", "ACCOUNTANT", "Payment declined", "A payment of ${{amount}} failed: {{reason}}.", "/payments", "HIGH")), 10, true);
        rule(org, "Escalate missed maintenance targets", "Notifies the property manager and owner-facing team when a work order misses its response target.",
                "SlaBreached", List.of(), List.of(notify("ROLE", "PROPERTY_MANAGER", "Maintenance target missed", "A {{priority}} work order is overdue.", "/maintenance", "HIGH")), 15, true);
        rule(org, "Thank residents for paying on time", "Sends a confirmation message when a payment is received.",
                "PaymentReceived", List.of(), List.of(notifyField("tenantId", "Thanks for your payment", "We received ${{amount}}. Reference {{reference}}.", "/payments")), 2, false);
        rule(org, "Tell owners when a lease is signed", "Notifies the owner when a new lease becomes active.",
                "LeaseActivated", List.of(), List.of(notifyField("ownerId", "New lease started", "A new resident has moved in at one of your properties.", "/leasing/leases")), 5, true);
        rule(org, "Notify owners of large repairs", "Tells owners when a completed repair cost more than $500.",
                "WorkOrderCompleted", List.of(cond("cost", "gt", 500)), List.of(notifyField("ownerId", "Repair completed", "{{title}} cost ${{cost}}.", "/maintenance")), 6, false);
    }
}
