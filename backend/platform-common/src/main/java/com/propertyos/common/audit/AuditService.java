package com.propertyos.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Append-only audit trail written in the same transaction as the change it records. */
@Component
public class AuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(String entityType, UUID entityId, String action, String fromState, String toState, Map<String, ?> details) {
        UserContext ctx = UserContextHolder.get();
        try {
            jdbc.update("insert into platform.audit_event (org_id, entity_type, entity_id, action, from_state, to_state, actor_id, actor_name, details) " +
                            "values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                    ctx == null ? null : ctx.orgId(), entityType, entityId, action, fromState, toState,
                    ctx == null ? null : ctx.userId(), ctx == null ? "System" : ctx.name(),
                    mapper.writeValueAsString(details == null ? Map.of() : details));
        } catch (Exception e) {
            throw new IllegalStateException("Audit write failed", e);
        }
    }

    /** Timeline for one entity, oldest first. Filtered by row-level security. */
    public List<Map<String, Object>> timeline(UUID entityId) {
        return jdbc.queryForList("select id, entity_type, action, from_state, to_state, actor_name, details::text as details, created_at " +
                "from platform.audit_event where entity_id = ? order by id", entityId);
    }
}
