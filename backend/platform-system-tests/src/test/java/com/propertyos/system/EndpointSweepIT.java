package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads every service's OpenAPI document and calls each read endpoint as every kind of demo user. List endpoints are
 * called first and their first row supplies the identifier for the matching detail endpoint. No call may fail with a
 * server error (5xx): a signed-in user must always get an answer or a clear 4xx.
 */
class EndpointSweepIT {
    private static final List<String> SERVICES = List.of("identity-service", "leasing-service", "billing-service",
            "maintenance-service", "workflow-service", "ai-service", "document-service");
    private static final Map<String, String> SPEC = Map.of("identity-service", "/api/identity/public/v3/api-docs",
            "leasing-service", "/api/leasing/public/v3/api-docs", "billing-service", "/api/billing/public/v3/api-docs",
            "maintenance-service", "/api/maintenance/public/v3/api-docs", "workflow-service", "/api/workflow/public/v3/api-docs",
            "ai-service", "/api/ai/public/v3/api-docs", "document-service", "/api/documents/public/v3/api-docs");

    @Test
    void everyReadEndpointAnswersForEveryPersona() {
        SystemEnvironment env = SystemEnvironment.get();
        List<String> failures = new ArrayList<>();
        int calls = 0;
        for (String role : env.demoRoleKeys()) {
            String org = role.equals("PLATFORM_ADMIN") ? "Platform" : "Summit";
            String token;
            try {
                token = env.token(role, org);
            } catch (AssertionError e) {
                continue;
            }
            for (String service : SERVICES) {
                ApiClient api = env.api(service);
                JsonNode paths = api.get(null, SPEC.get(service)).body().path("paths");
                Iterator<String> names = paths.fieldNames();
                while (names.hasNext()) {
                    String path = names.next();
                    if (!paths.path(path).has("get") || path.contains("/public/")) continue;
                    String url = path;
                    if (path.contains("{")) {
                        String collection = path.substring(0, path.indexOf("/{"));
                        if (collection.contains("{") || path.indexOf("/{") != path.lastIndexOf("/{")) continue;
                        ApiClient.Reply list = api.get(token, collection);
                        calls++;
                        if (list.status() >= 500) failures.add(role + " GET " + collection + " -> " + list.status());
                        JsonNode rows = list.body() == null ? null : (list.body().isArray() ? list.body() : list.body().path("items"));
                        if (rows == null || !rows.isArray() || rows.isEmpty() || !rows.get(0).has("id")) continue;
                        url = collection + "/" + rows.get(0).path("id").asText() + path.substring(path.indexOf('}') + 1);
                    } else if (path.contains("deadlines") || path.endsWith("/export")) {
                        url = path;
                    }
                    ApiClient.Reply r = api.get(token, url);
                    calls++;
                    if (r.status() >= 500) failures.add(role + " GET " + url + " -> " + r.status());
                }
            }
        }
        assertTrue(calls > 300, "Expected a broad sweep but only made " + calls + " calls");
        assertTrue(failures.isEmpty(), "Server errors:\n" + String.join("\n", failures));
    }
}
