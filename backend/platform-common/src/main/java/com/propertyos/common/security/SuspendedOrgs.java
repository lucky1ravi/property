package com.propertyos.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.ServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The companies PropertyOS has suspended. Every service keeps this list in memory and refreshes it from the identity
 * service every few seconds, so a suspension reaches signed-in users, the public site and background jobs within
 * moments, with no database call per request. If the identity service can't be reached, the last known list is kept.
 */
@Component
public class SuspendedOrgs {
    private static final Logger log = LoggerFactory.getLogger(SuspendedOrgs.class);

    private final ObjectProvider<ServiceClient> client;
    private volatile Set<UUID> ids = Set.of();

    public SuspendedOrgs(ObjectProvider<ServiceClient> client) {
        this.client = client;
    }

    /** True when the company is suspended. */
    public boolean contains(UUID orgId) {
        return orgId != null && ids.contains(orgId);
    }

    /** Replaces the list now (the identity service calls this the moment it suspends or restores a company). */
    public void replace(Collection<UUID> suspended) {
        this.ids = Set.copyOf(new HashSet<>(suspended));
    }

    /** The suspended company ids as a comma-separated string, for use with {@code <> all (string_to_array(?, ','))} in public queries. */
    public String asCsv() {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    /** Pulls the current list from the identity service. */
    @Scheduled(fixedDelay = 10_000, initialDelay = 4_000)
    public void refresh() {
        ServiceClient c = client.getIfAvailable();
        if (c == null) return;
        try {
            JsonNode n = c.get(ServiceClient.Service.IDENTITY, "/internal/identity/suspended-orgs");
            Set<UUID> next = new HashSet<>();
            for (JsonNode id : n) next.add(UUID.fromString(id.asText()));
            this.ids = Set.copyOf(next);
        } catch (Exception e) {
            log.debug("Could not refresh the suspended companies: {}", e.getMessage());
        }
    }
}
