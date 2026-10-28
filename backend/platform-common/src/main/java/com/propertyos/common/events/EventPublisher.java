package com.propertyos.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox: writes the event in the same database transaction as the
 * business change, so the change and its event commit or roll back together.
 */
@Component
public class EventPublisher {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EventPublisher(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void publish(String type, String aggregateType, UUID aggregateId, Map<String, ?> payload) {
        UserContext ctx = UserContextHolder.get();
        UUID org = ctx == null ? null : ctx.orgId();
        publish(org, type, aggregateType, aggregateId, payload);
    }

    public void publish(UUID orgId, String type, String aggregateType, UUID aggregateId, Map<String, ?> payload) {
        UserContext ctx = UserContextHolder.get();
        try {
            jdbc.update("insert into platform.event_log (org_id, event_type, aggregate_type, aggregate_id, payload, actor_id, actor_name) " +
                            "values (?, ?, ?, ?, ?::jsonb, ?, ?)",
                    orgId, type, aggregateType, aggregateId, mapper.writeValueAsString(payload),
                    ctx == null ? null : ctx.userId(), ctx == null ? "System" : ctx.name());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event payload is not serialisable", e);
        }
    }
}
