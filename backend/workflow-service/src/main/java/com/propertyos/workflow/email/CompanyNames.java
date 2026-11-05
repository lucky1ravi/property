package com.propertyos.workflow.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.ServiceClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The name of a company, for the "from" line of an email. Looked up from the identity service and remembered; falls back to a plain phrase when it cannot be found. */
@Component
public class CompanyNames {
    static final String FALLBACK = "Your property manager";
    private final ServiceClient client;
    private final Map<UUID, String> known = new ConcurrentHashMap<>();

    public CompanyNames(ServiceClient client) {
        this.client = client;
    }

    public String of(UUID org) {
        if (org == null) return FALLBACK;
        String cached = known.get(org);
        if (cached != null) return cached;
        try {
            JsonNode n = client.get(ServiceClient.Service.IDENTITY, "/internal/org-names?ids=" + org);
            String name = n.path(org.toString()).asText("");
            if (!name.isBlank()) {
                known.put(org, name);
                return name;
            }
        } catch (RuntimeException ignored) {
            // the email is still worth sending without the company name
        }
        return FALLBACK;
    }
}
