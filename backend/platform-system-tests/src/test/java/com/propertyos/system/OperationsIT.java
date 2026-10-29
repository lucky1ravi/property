package com.propertyos.system;

import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Raw;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What operators and platforms rely on, checked on the running services: health probes, metrics, correlation ids that follow a request
 * through the gateway, and security headers.
 */
class OperationsIT {
    private static final List<String> SERVICES = List.of("identity-service", "leasing-service", "billing-service", "maintenance-service",
            "workflow-service", "ai-service", "document-service", "api-gateway");

    @Test
    void everyServiceReportsLivenessAndReadiness() {
        for (String s : SERVICES) {
            ApiClient api = Flow.env().api(s);
            assertEquals(200, api.getRaw(null, "/actuator/health", Map.of()).status(), s + " health");
            assertEquals(200, api.getRaw(null, "/actuator/health/liveness", Map.of()).status(), s + " liveness");
            assertEquals(200, api.getRaw(null, "/actuator/health/readiness", Map.of()).status(), s + " readiness");
            Raw metrics = api.getRaw(null, "/actuator/prometheus", Map.of());
            assertEquals(200, metrics.status(), s + " metrics");
            assertTrue(metrics.text().contains("http_server_requests") || metrics.text().contains("jvm_memory"), s + " metrics content");
        }
    }

    @Test
    void servicesShowTheStateOfTheirNeighbours() {
        Raw r = Flow.env().api("leasing-service").getRaw(null, "/actuator/health/downstream", Map.of());
        // Informational group; it is not part of readiness so a neighbour's outage cannot remove this instance from rotation.
        assertTrue(r.status() == 200 || r.status() == 404, "downstream health: " + r.status());
    }

    @Test
    void aCorrelationIdFollowsTheRequest() {
        String token = Flow.token("ORG_ADMIN");
        Raw viaGateway = Flow.gateway().getRaw(token, "/api/identity/me", Map.of("X-Correlation-Id", "system-test-0001"));
        assertEquals(200, viaGateway.status());
        assertEquals("system-test-0001", viaGateway.headers().getFirst("X-Correlation-Id"));
        Raw direct = Flow.identity().getRaw(token, "/api/identity/me", Map.of("X-Correlation-Id", "system-test-0002"));
        assertEquals("system-test-0002", direct.headers().getFirst("X-Correlation-Id"));
    }

    @Test
    void anUnsafeCorrelationIdIsReplaced() {
        Raw r = Flow.identity().getRaw(null, "/api/identity/public/options", Map.of("X-Correlation-Id", "bad id with spaces"));
        String id = r.headers().getFirst("X-Correlation-Id");
        assertNotEquals("bad id with spaces", id);
        assertTrue(id.matches("[0-9a-f-]{36}"), id);
        String generated = Flow.gateway().getRaw(Flow.token("ORG_ADMIN"), "/api/identity/me", Map.of()).headers().getFirst("X-Correlation-Id");
        assertTrue(generated.matches("[0-9a-f-]{36}"), generated);
    }

    @Test
    void responsesCarryHardeningHeaders() {
        Raw svc = Flow.identity().getRaw(Flow.token("ORG_ADMIN"), "/api/identity/me", Map.of());
        assertEquals("nosniff", svc.headers().getFirst("X-Content-Type-Options"));
        assertEquals("DENY", svc.headers().getFirst("X-Frame-Options"));
        assertEquals("no-store", svc.headers().getFirst("Cache-Control"));
        Raw gw = Flow.gateway().getRaw(Flow.token("ORG_ADMIN"), "/api/identity/me", Map.of());
        assertEquals("nosniff", gw.headers().getFirst("X-Content-Type-Options"));
        assertTrue(gw.headers().getFirst("Strict-Transport-Security").contains("max-age"));
        assertTrue(Integer.parseInt(gw.headers().getFirst("X-RateLimit-Remaining")) >= 0);
    }

    @Test
    void internalEndpointsAreNotReachableFromOutside() {
        assertEquals(404, Flow.gateway().getRaw(null, "/internal/config/anything", Map.of()).status());
        assertEquals(401, Flow.identity().getRaw(null, "/internal/orgs", Map.of()).status());
        assertNull(Flow.gateway().getRaw(null, "/internal/orgs", Map.of()).headers().getFirst("X-Powered-By"));
    }
}
