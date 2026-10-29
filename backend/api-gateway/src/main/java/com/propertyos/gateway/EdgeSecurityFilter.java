package com.propertyos.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Edge controls applied to every request:
 * <ol>
 *   <li>Blocks /internal/** (service-to-service only) from the internet</li>
 *   <li>Validates the JWT on all non-public API calls (services validate again: zero trust)</li>
 *   <li>Token-bucket rate limiting per user, or per IP for public endpoints; returns 429 with Retry-After</li>
 *   <li>Adds a correlation id and security headers</li>
 * </ol>
 */
@Component
public class EdgeSecurityFilter implements GlobalFilter, Ordered {
    private static final org.slf4j.Logger access = org.slf4j.LoggerFactory.getLogger("access");
    private final SecretKey key;
    private final int userPerMinute;
    private final int publicPerMinute;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public EdgeSecurityFilter(@Value("${propertyos.jwt.secret}") String secret,
                              @Value("${propertyos.rate-limit.user-per-minute:600}") int userPerMinute,
                              @Value("${propertyos.rate-limit.public-per-minute:60}") int publicPerMinute) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.userPerMinute = userPerMinute;
        this.publicPerMinute = publicPerMinute;
    }

    /** Token bucket: refills continuously; capacity allows short bursts. */
    static final class Bucket {
        private final double capacity;
        private final double refillPerMs;
        private double tokens;
        private long last;

        Bucket(int perMinute) {
            this.capacity = perMinute;
            this.refillPerMs = perMinute / 60_000.0;
            this.tokens = perMinute;
            this.last = System.currentTimeMillis();
        }

        synchronized long tryConsume() {
            long now = System.currentTimeMillis();
            tokens = Math.min(capacity, tokens + (now - last) * refillPerMs);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return -1;
            }
            return (long) Math.ceil((1 - tokens) / refillPerMs / 1000.0);
        }

        synchronized int remaining() {
            return (int) tokens;
        }
    }

    /**
     * Applies the edge controls in order: blocks {@code /internal/**}, validates the JWT on non-public paths, rate-limits, then adds a correlation id and security headers.
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String path = req.getPath().value();
        ServerHttpResponse res = exchange.getResponse();
        HttpHeaders h = res.getHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "strict-origin-when-cross-origin");
        h.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");

        if (path.startsWith("/internal")) return error(res, HttpStatus.NOT_FOUND, "Not found");
        if ("OPTIONS".equals(req.getMethod().name()) || !path.startsWith("/api/")) return chain.filter(exchange);

        String correlation = req.getHeaders().getFirst("X-Correlation-Id");
        if (correlation == null || correlation.isBlank()) correlation = UUID.randomUUID().toString();
        h.set("X-Correlation-Id", correlation);

        boolean isPublic = path.contains("/public/");
        String bucketKey;
        int limit;
        if (isPublic) {
            String ip = req.getHeaders().getFirst("X-Forwarded-For");
            if (ip == null && req.getRemoteAddress() != null) ip = req.getRemoteAddress().getAddress().getHostAddress();
            bucketKey = "ip:" + (ip == null ? "unknown" : ip.split(",")[0].trim());
            limit = publicPerMinute;
        } else {
            String auth = req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (auth == null || !auth.startsWith("Bearer ")) return error(res, HttpStatus.UNAUTHORIZED, "Sign-in required");
            try {
                Claims c = Jwts.parser().verifyWith(key).requireIssuer("propertyos").build().parseSignedClaims(auth.substring(7)).getPayload();
                bucketKey = "user:" + c.getSubject();
            } catch (Exception e) {
                return error(res, HttpStatus.UNAUTHORIZED, "Session expired or invalid. Please sign in again.");
            }
            limit = userPerMinute;
        }
        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> new Bucket(limit));
        long retryAfter = bucket.tryConsume();
        if (retryAfter >= 0) {
            h.set("Retry-After", String.valueOf(Math.max(1, retryAfter)));
            return error(res, HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please retry shortly.");
        }
        h.set("X-RateLimit-Remaining", String.valueOf(bucket.remaining()));
        String finalCorrelation = correlation;
        ServerHttpRequest mutated = req.mutate().header("X-Correlation-Id", finalCorrelation).build();
        long started = System.nanoTime();
        return chain.filter(exchange.mutate().request(mutated).build()).doFinally(signal ->
                access.info("correlationId={} {} {} -> {} in {} ms", finalCorrelation, req.getMethod(), path, res.getStatusCode() == null ? 0 : res.getStatusCode().value(),
                        (System.nanoTime() - started) / 1_000_000));
    }

    private Mono<Void> error(ServerHttpResponse res, HttpStatus status, String message) {
        res.setStatusCode(status);
        res.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String body = "{\"status\":" + status.value() + ",\"title\":\"" + status.getReasonPhrase() + "\",\"detail\":\"" + message + "\"}";
        DataBuffer buf = res.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return res.writeWith(Mono.just(buf));
    }

    /**
     * Runs before the other gateway filters.
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
