package com.propertyos.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/** With every service unreachable the gateway still answers quickly and clearly, and exposes its own health and metrics. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "propertyos.jwt.secret=gateway-test-secret-gateway-test-secret-0123456789",
        "propertyos.services.identity=http://127.0.0.1:1", "propertyos.services.leasing=http://127.0.0.1:1",
        "propertyos.services.billing=http://127.0.0.1:1", "propertyos.services.maintenance=http://127.0.0.1:1",
        "propertyos.services.workflow=http://127.0.0.1:1", "propertyos.services.ai=http://127.0.0.1:1",
        "propertyos.services.documents=http://127.0.0.1:1"})
@AutoConfigureWebTestClient(timeout = "60000")
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class GatewayResilienceTest {
    @Autowired
    private WebTestClient web;

    private static String token() {
        return Jwts.builder().issuer("propertyos").subject("u1").expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor("gateway-test-secret-gateway-test-secret-0123456789".getBytes(StandardCharsets.UTF_8))).compact();
    }

    @Test
    void aDownServiceGivesAQuick503WithRetryAdvice() {
        for (int i = 0; i < 3; i++)
            web.get().uri("/api/leasing/properties").header("Authorization", "Bearer " + token()).exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().valueEquals("Retry-After", "10")
                    .expectHeader().exists("X-Correlation-Id")
                    .expectBody().jsonPath("$.detail").value(d -> assertThat(d.toString()).contains("leasing"));
    }

    @Test
    void writesToADownServiceAreAlsoRefusedClearly() {
        web.post().uri("/api/billing/invoices").header("Authorization", "Bearer " + token()).bodyValue("{}").exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void aTokenIsStillRequired() {
        web.get().uri("/api/leasing/properties").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void internalPathsStayHidden() {
        web.get().uri("/internal/config/x").exchange().expectStatus().isNotFound();
    }

    @Test
    void healthProbesAndMetricsAreAvailable() {
        web.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        web.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        web.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
    }
}
