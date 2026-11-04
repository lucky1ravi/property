package com.propertyos.workflow.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.rls.Db;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the platform's events into webhook deliveries. For each event it finds the company's active endpoints that subscribed to it and writes one pending
 * delivery per endpoint; the dispatcher sends them. Writing a row per endpoint (not sending here) means a slow receiver never delays other work, and an
 * event is delivered at most once per endpoint even if this handler sees it twice.
 */
@Component
public class WebhookEvents implements EventHandler {
    private final Db db;
    private final ConfigClient config;
    private final ObjectMapper mapper;

    public WebhookEvents(Db db, ConfigClient config, ObjectMapper mapper) {
        this.db = db;
        this.config = config;
        this.mapper = mapper;
    }

    @Override
    public String subscriber() {
        return "workflow.webhooks";
    }

    @Override
    public Set<String> eventTypes() {
        return WebhookEventTypes.domainEvents();
    }

    /** The JSON a receiver gets: an id, the type, when it happened, and the facts. {@code data.id} is the thing the event is about. */
    static String envelope(ObjectMapper mapper, String eventId, String type, java.time.OffsetDateTime at, UUID aggregateId, Map<String, ?> data, boolean test) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", eventId);
        root.put("type", type);
        root.put("text", WebhookEventTypes.summary(type, data, test));
        root.put("created_at", at.toInstant().toString());
        if (test) root.put("test", true);
        ObjectNode d = root.putObject("data");
        if (aggregateId != null) d.put("id", aggregateId.toString());
        data.forEach((k, v) -> { if (!"id".equals(k) || aggregateId == null) d.set(k, mapper.valueToTree(v)); });
        return root.toString();
    }

    @Override
    @Transactional
    public void handle(DomainEvent e) {
        if (e.orgId() == null) return;
        var type = WebhookEventTypes.byDomainEvent(e.type());
        if (type.isEmpty()) return;
        if (!config.forOrg(e.orgId()).path("features").path("WEBHOOKS").asBoolean(true)) return;   // the plan or the operator has switched webhooks off for this company
        List<Map<String, Object>> endpoints = db.list("select id from workflow.webhook_endpoint where org_id = ? and active and ? = any (events)", e.orgId(), type.get().key());
        if (endpoints.isEmpty()) return;
        Map<String, Object> data = e.payload() == null ? Map.of() : mapper.convertValue(e.payload(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        String eventId = "evt_" + e.id();
        String body = envelope(mapper, eventId, type.get().key(), e.createdAt(), e.aggregateId(), data, false);
        for (Map<String, Object> ep : endpoints)
            db.update("""
                    insert into workflow.webhook_delivery (id, org_id, endpoint_id, event_id, event_type, payload, status, next_attempt_at)
                    values (?,?,?,?,?,?::jsonb,'PENDING', now())
                    on conflict (endpoint_id, event_id) do nothing""", UUID.randomUUID(), e.orgId(), ep.get("id"), eventId, type.get().key(), body);
    }
}
