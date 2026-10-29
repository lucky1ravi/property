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
    public static void main(String[] args) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        SpringApplication.run(GatewayApplication.class, args);
    }

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
                .route("identity", r -> r.path("/api/identity/**").uri(identity))
                .route("leasing", r -> r.path("/api/leasing/**").uri(leasing))
                .route("billing", r -> r.path("/api/billing/**").uri(billing))
                .route("maintenance", r -> r.path("/api/maintenance/**").uri(maintenance))
                .route("workflow", r -> r.path("/api/workflow/**").uri(workflow))
                .route("ai", r -> r.path("/api/ai/**").uri(ai))
                .route("documents", r -> r.path("/api/documents/**").uri(documents))
                .build();
    }

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
