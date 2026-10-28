package com.propertyos.common.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/** An event read back from the platform event log. */
public record DomainEvent(
        long id,
        UUID orgId,
        String type,
        String aggregateType,
        UUID aggregateId,
        JsonNode payload,
        UUID actorId,
        String actorName,
        OffsetDateTime createdAt
) {
    public String text(String field) {
        JsonNode n = payload.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    public UUID uuid(String field) {
        String s = text(field);
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }
}
