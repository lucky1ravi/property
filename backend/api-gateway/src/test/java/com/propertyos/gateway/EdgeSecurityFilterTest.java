package com.propertyos.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The edge rules: internal paths hidden, sign-in required, rate limits enforced, headers added. */
class EdgeSecurityFilterTest {
    private static final String SECRET = "unit-test-secret-unit-test-secret-unit-test-secret";
    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final GatewayFilterChain chain = ex -> {
        forwarded.set(ex);
        return Mono.empty();
    };

    private static String token(String issuer, String secret) {
        return Jwts.builder().issuer(issuer).subject("user-1").expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private MockServerWebExchange run(EdgeSecurityFilter f, MockServerHttpRequest.BaseBuilder<?> req) {
        MockServerWebExchange ex = MockServerWebExchange.from(req.build());
        f.filter(ex, chain).block();
        return ex;
    }

    @Test
    void internalPathsAreNotReachable() {
        MockServerWebExchange ex = run(new EdgeSecurityFilter(SECRET, 10, 10), MockServerHttpRequest.get("/internal/config/1"));
        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getResponse().getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    void nonApiPathsAndPreflightPassThrough() {
        EdgeSecurityFilter f = new EdgeSecurityFilter(SECRET, 10, 10);
        run(f, MockServerHttpRequest.get("/actuator/health"));
        assertThat(forwarded.getAndSet(null)).isNotNull();
        run(f, MockServerHttpRequest.options("/api/identity/me"));
        assertThat(forwarded.getAndSet(null)).isNotNull();
    }

    @Test
    void protectedCallsNeedAValidToken() {
        EdgeSecurityFilter f = new EdgeSecurityFilter(SECRET, 10, 10);
        assertThat(run(f, MockServerHttpRequest.get("/api/identity/me")).getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(run(f, MockServerHttpRequest.get("/api/identity/me").header("Authorization", "Bearer garbage")).getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        String wrongIssuer = token("someone-else", SECRET);
        assertThat(run(f, MockServerHttpRequest.get("/api/identity/me").header("Authorization", "Bearer " + wrongIssuer)).getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(forwarded.get()).isNull();
    }

    @Test
    void validCallsGetACorrelationIdAndAreForwarded() {
        EdgeSecurityFilter f = new EdgeSecurityFilter(SECRET, 10, 10);
        String t = token("propertyos", SECRET);
        MockServerWebExchange ex = run(f, MockServerHttpRequest.get("/api/identity/me").header("Authorization", "Bearer " + t));
        assertThat(forwarded.get()).isNotNull();
        assertThat(ex.getResponse().getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
        assertThat(ex.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("9");
        run(f, MockServerHttpRequest.get("/api/identity/me").header("Authorization", "Bearer " + t).header("X-Correlation-Id", "abc-1"));
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-Correlation-Id")).isEqualTo("abc-1");
    }

    @Test
    void usersAreRateLimited() {
        EdgeSecurityFilter f = new EdgeSecurityFilter(SECRET, 2, 10);
        String t = token("propertyos", SECRET);
        run(f, MockServerHttpRequest.get("/api/x").header("Authorization", "Bearer " + t));
        run(f, MockServerHttpRequest.get("/api/x").header("Authorization", "Bearer " + t));
        MockServerWebExchange third = run(f, MockServerHttpRequest.get("/api/x").header("Authorization", "Bearer " + t));
        assertThat(third.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getResponse().getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    @Test
    void publicCallsAreLimitedPerAddress() {
        EdgeSecurityFilter f = new EdgeSecurityFilter(SECRET, 10, 1);
        run(f, MockServerHttpRequest.post("/api/identity/public/login").header("X-Forwarded-For", "9.9.9.9, 1.1.1.1"));
        assertThat(forwarded.get()).isNotNull();
        MockServerWebExchange second = run(f, MockServerHttpRequest.post("/api/identity/public/login").header("X-Forwarded-For", "9.9.9.9"));
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        MockServerWebExchange other = run(f, MockServerHttpRequest.post("/api/identity/public/login").remoteAddress(new java.net.InetSocketAddress("8.8.8.8", 1)));
        assertThat(other.getResponse().getStatusCode()).isNull();
        run(f, MockServerHttpRequest.post("/api/identity/public/login"));
        assertThat(new EdgeSecurityFilter(SECRET, 1, 1).getOrder()).isLessThan(0);
    }
}
