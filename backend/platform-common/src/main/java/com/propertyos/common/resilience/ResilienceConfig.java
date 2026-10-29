package com.propertyos.common.resilience;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Settings for calls between services. Defaults suit a small cluster; tune them per environment with the {@code propertyos.resilience.*} properties.
 */
@Configuration
public class ResilienceConfig {
    /**
     * Builds the shared protection for outbound service calls.
     */
    @Bean
    public Resilience resilience(@Value("${propertyos.resilience.failure-rate-percent:50}") float failureRate,
                                 @Value("${propertyos.resilience.minimum-calls:10}") int minimumCalls,
                                 @Value("${propertyos.resilience.open-seconds:20}") int openSeconds,
                                 @Value("${propertyos.resilience.max-concurrent:50}") int maxConcurrent,
                                 @Value("${propertyos.resilience.retry-attempts:3}") int retryAttempts,
                                 @Value("${propertyos.resilience.retry-backoff-ms:150}") int backoffMs) {
        return new Resilience(failureRate, minimumCalls, Duration.ofSeconds(openSeconds), maxConcurrent, retryAttempts, Duration.ofMillis(backoffMs));
    }
}
