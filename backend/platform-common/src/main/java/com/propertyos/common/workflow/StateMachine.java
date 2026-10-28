package com.propertyos.common.workflow;

import com.propertyos.common.audit.AuditService;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Applies a workflow transition to a record: checks the configured workflow and the
 * caller's permission and approval limit, updates the status and writes the audit trail.
 * Call inside a transaction; the caller publishes domain events for the new state.
 */
@Component
public class StateMachine {
    private final WorkflowEngine engine;
    private final Db db;
    private final AuditService audit;

    public StateMachine(WorkflowEngine engine, Db db, AuditService audit) {
        this.engine = engine;
        this.db = db;
        this.audit = audit;
    }

    public record Outcome(String from, String to, WorkflowDefinition.Transition transition) {
    }

    public Outcome apply(String entityType, String table, UUID id, String workflowKey, String action, String reason,
                         BigDecimal amount, Map<String, ?> details) {
        String current = db.value(String.class, "select status from " + table + " where id = ?", id);
        if (current == null) throw com.propertyos.common.web.ApiException.notFound(entityType);
        WorkflowDefinition.Transition t = engine.authorize(workflowKey, current, action, reason, amount);
        db.update("update " + table + " set status = ?, updated_at = now() where id = ?", t.to(), id);
        Map<String, Object> d = new HashMap<>();
        if (details != null) d.putAll(details);
        if (reason != null && !reason.isBlank()) d.put("reason", reason);
        audit.record(entityType, id, action, current, t.to(), d);
        return new Outcome(current, t.to(), t);
    }

    /** Transition performed by the system (automation or AI), recorded as such in the audit trail. */
    public Outcome applyAsSystem(String entityType, String table, UUID id, String workflowKey, String action, String reason, Map<String, ?> details) {
        UserContext current = UserContextHolder.get();
        UUID org = current == null ? null : current.orgId();
        return UserContextHolder.runAs(UserContext.system(org), () -> apply(entityType, table, id, workflowKey, action, reason, null, details));
    }

    /** Records an initial state (creation) in the audit trail. */
    public void created(String entityType, UUID id, String initialState, Map<String, ?> details) {
        audit.record(entityType, id, "CREATED", null, initialState, details);
    }
}
