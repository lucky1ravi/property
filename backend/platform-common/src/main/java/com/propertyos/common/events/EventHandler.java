package com.propertyos.common.events;

import java.util.Set;

/**
 * Subscribes a service to event types. Each handler keeps its own offset, so services
 * consume independently, like Kafka consumer groups. Handlers must be idempotent.
 */
public interface EventHandler {
    /** Unique, stable subscriber name, for example "billing.lease-activated". */
    String subscriber();

    /** Event types to receive; "*" receives everything. */
    Set<String> eventTypes();

    void handle(DomainEvent event);
}
