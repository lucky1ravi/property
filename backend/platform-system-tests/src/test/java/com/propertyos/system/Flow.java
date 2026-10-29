package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared helpers for the system tests: sign-in as a demo persona, a client per service, and a tolerant way to drive
 * workflows by following the actions each record offers.
 */
final class Flow {
    private Flow() {
    }

    static SystemEnvironment env() {
        return SystemEnvironment.get();
    }

    /** Bearer token of a demo user of the main demo company (or the platform operator). */
    static String token(String role) {
        return env().token(role, "PLATFORM_ADMIN".equals(role) ? "Platform" : "Summit");
    }

    static ApiClient identity() { return env().api("identity-service"); }
    static ApiClient leasing() { return env().api("leasing-service"); }
    static ApiClient billing() { return env().api("billing-service"); }
    static ApiClient maintenance() { return env().api("maintenance-service"); }
    static ApiClient workflow() { return env().api("workflow-service"); }
    static ApiClient ai() { return env().api("ai-service"); }
    static ApiClient documents() { return env().api("document-service"); }
    static ApiClient gateway() { return env().api("api-gateway"); }

    /** A map from alternating keys and values, for request bodies with more than ten fields. */
    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        return m;
    }

    /** The rows of a list response, whether it is a bare array or an object with an {@code items} array. */
    static JsonNode rows(Reply r) {
        JsonNode b = r.body();
        if (b == null) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        return b.isArray() ? b : b.path("items");
    }

    /** Asserts the call succeeded (2xx) and returns it. */
    static Reply ok(Reply r, String what) {
        assertTrue(r.status() >= 200 && r.status() < 300, what + " should succeed but returned " + r.status() + ": " + r.body());
        return r;
    }

    /** Collects calls that fail with a server error so one run reports every broken endpoint, not just the first. */
    static final class Soft {
        private final List<String> failures = new ArrayList<>();
        private int calls;

        Reply seen(Reply r, String what) {
            calls++;
            if (r.status() >= 500) failures.add(what + " -> " + r.status() + " " + r.body());
            return r;
        }

        int calls() {
            return calls;
        }

        void assertClean() {
            assertTrue(failures.isEmpty(), "Server errors:\n" + String.join("\n", failures));
        }
    }

    /**
     * Follows the actions a record offers, choosing a different one at each step according to {@code seed}, so that
     * running it over many records exercises many branches of the workflow.
     *
     * @param base  collection path, for example {@code /api/leasing/leases}
     * @param extra extra fields to send with every action (for example a vendor id)
     */
    static void walk(Soft soft, ApiClient api, String token, String base, String id, int seed, int steps, Map<String, Object> extra) {
        for (int step = 0; step < steps; step++) {
            Reply detail = soft.seen(api.get(token, base + "/" + id), "GET " + base + "/" + id);
            JsonNode actions = detail.body() == null ? null : detail.body().path("actions");
            if (detail.status() != 200 || actions == null || !actions.isArray() || actions.isEmpty()) return;
            for (int attempt = 0; attempt < actions.size(); attempt++) {
                String action = actions.get((seed + step + attempt) % actions.size()).path("action").asText();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("reason", "Automated system test");
                body.put("signature", detail.body().path("tenant_name").asText("Test Signer"));
                body.put("scheduledFor", OffsetDateTime.now().plusDays(2).toString());
                body.put("cost", 240);
                body.put("amount", 240);
                body.put("rating", 4);
                body.put("comment", "Good work");
                body.put("notes", "Finished");
                body.put("moveOutDate", LocalDate.now().plusDays(45).toString());
                body.putAll(extra);
                Reply r = soft.seen(api.post(token, base + "/" + id + "/actions/" + action, body), "POST " + base + "/" + id + "/actions/" + action);
                if (r.status() < 300) break;
            }
        }
    }
}
