package com.propertyos.common.client;

import com.propertyos.common.observability.CorrelationFilter;
import com.propertyos.common.observability.DownstreamHealth;
import com.propertyos.common.resilience.Resilience;
import com.propertyos.common.resilience.ServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.HttpClientErrorException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Calls between services carry the correlation id and the internal credentials, and fail fast once a service is down. */
class ServiceClientTest {
    private HttpServer server;
    private final AtomicReference<String> correlation = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private ServiceClient client;
    private Resilience resilience;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            correlation.set(ex.getRequestHeaders().getFirst(CorrelationFilter.HEADER));
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        MockEnvironment env = new MockEnvironment().withProperty("propertyos.services.billing", "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("propertyos.services.leasing", "http://127.0.0.1:1");
        resilience = new Resilience(50, 4, Duration.ofSeconds(30), 10, 2, Duration.ofMillis(1));
        client = new ServiceClient(env, "internal-token", resilience);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        MDC.clear();
    }

    @Test
    void forwardsTheCorrelationId() {
        MDC.put(CorrelationFilter.MDC_KEY, "trace-abcdefgh");
        assertThat(client.get(ServiceClient.Service.BILLING, "/internal/x").path("ok").asBoolean()).isTrue();
        assertThat(correlation.get()).isEqualTo("trace-abcdefgh");
        client.post(ServiceClient.Service.BILLING, "/internal/x", null);
        client.postAsUser(ServiceClient.Service.BILLING, "/internal/x", java.util.Map.of("a", 1), "Bearer t");
        MDC.clear();
        client.get(ServiceClient.Service.BILLING, "/internal/x");
        assertThat(correlation.get()).matches("[0-9a-f-]{36}");
    }

    @Test
    void clientErrorsAreNotRetried() {
        status = 404;
        assertThatThrownBy(() -> client.get(ServiceClient.Service.BILLING, "/internal/missing")).isInstanceOf(HttpClientErrorException.class);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void anUnreachableServiceTripsTheCircuit() {
        for (int i = 0; i < 8; i++)
            assertThatThrownBy(() -> client.post(ServiceClient.Service.LEASING, "/internal/x", null)).isInstanceOf(org.springframework.web.client.RestClientException.class);
        assertThat(resilience.state("LEASING")).isEqualTo("OPEN");
        assertThatThrownBy(() -> client.get(ServiceClient.Service.LEASING, "/internal/x")).isInstanceOf(ServiceUnavailableException.class);
        var health = new DownstreamHealth(resilience).health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("leasing", "OPEN").containsEntry("billing", "CLOSED");
    }
}
