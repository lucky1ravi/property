package com.propertyos.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** Single entry point: routing, token validation, rate limiting, CORS and security headers. */
@SpringBootApplication
public class GatewayApplication {
    /**
     * Starts the gateway in UTC.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        SpringApplication.run(GatewayApplication.class, args);
    }

    /**
     * Defines which {@code /api/...} paths go to which service.
     */
    @Bean
    public RouteLocator routes(RouteLocatorBuilder b,
                               @Value("${propertyos.services.identity}") String identity,
                               @Value("${propertyos.services.leasing}") String leasing,
                               @Value("${propertyos.services.billing}") String billing,
                               @Value("${propertyos.services.maintenance}") String maintenance,
                               @Value("${propertyos.services.workflow}") String workflow,
                               @Value("${propertyos.services.ai}") String ai,
                               @Value("${propertyos.services.documents}") String documents) {
        return b.routes()
                .route("identity", r -> protect(r.path("/api/identity/**"), "identity").uri(identity))
                .route("leasing", r -> protect(r.path("/api/leasing/**"), "leasing").uri(leasing))
                .route("billing", r -> protect(r.path("/api/billing/**"), "billing").uri(billing))
                .route("maintenance", r -> protect(r.path("/api/maintenance/**"), "maintenance").uri(maintenance))
                .route("workflow", r -> protect(r.path("/api/workflow/**"), "workflow").uri(workflow))
                .route("ai", r -> protect(r.path("/api/ai/**"), "ai").uri(ai))
                .route("documents", r -> protect(r.path("/api/documents/**"), "documents").uri(documents))
                .build();
    }

    /**
     * Puts a circuit breaker and safe-read retries in front of one service. When a service is down, callers get a quick 503 with
     * {@code Retry-After} from {@link FallbackController} instead of waiting for a timeout; reads are retried once or twice because they change nothing.
     */
    private static org.springframework.cloud.gateway.route.builder.UriSpec protect(org.springframework.cloud.gateway.route.builder.BooleanSpec spec, String name) {
        // Order matters: the circuit breaker is the outer layer, so retries happen inside it and the fallback answer is never retried.
        return spec.filters(f -> f
                .circuitBreaker(c -> c.setName(name).setFallbackUri("forward:/fallback/" + name))
                .retry(c -> c.setRetries(2).setMethods(org.springframework.http.HttpMethod.GET)
                        .setStatuses(org.springframework.http.HttpStatus.BAD_GATEWAY, org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                        .setBackoff(Duration.ofMillis(100), Duration.ofSeconds(1), 2, true)));
    }

    /**
     * Circuit-breaker settings shared by every route. The time limit is above the slowest legitimate call (AI, up to 90 seconds).
     */
    @Bean
    public org.springframework.cloud.client.circuitbreaker.Customizer<org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory> circuitBreakerDefaults(
            @Value("${propertyos.resilience.open-seconds:20}") int openSeconds) {
        return factory -> factory.configureDefault(id -> new org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .failureRateThreshold(50).minimumNumberOfCalls(10).slidingWindowSize(20)
                        .waitDurationInOpenState(Duration.ofSeconds(openSeconds)).permittedNumberOfCallsInHalfOpenState(3).build())
                .timeLimiterConfig(io.github.resilience4j.timelimiter.TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(95)).build())
                .build());
    }

    /**
     * Allows browser calls from the configured origins ({@code ALLOWED_ORIGINS}).
     */
    @Bean
    public CorsWebFilter corsWebFilter(@Value("${propertyos.allowed-origins:*}") String origins) {
        CorsConfiguration c = new CorsConfiguration();
        List<String> list = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        c.setAllowedOriginPatterns(list);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        c.setExposedHeaders(List.of("X-Correlation-Id", "X-RateLimit-Remaining", "Retry-After", "Content-Disposition"));
        c.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return new CorsWebFilter(src);
    }
}
