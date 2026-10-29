package com.propertyos.common.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Protects calls to one downstream service with a bulkhead (limits concurrent calls), a circuit breaker (stops calling a failing service)
 * and, for safe reads, a retry with exponential backoff.
 * <p>
 * Client errors (4xx) are the caller's mistake, not a sick service, so they never open the circuit and are never retried.
 */
public class Resilience {
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();
    private final CircuitBreakerConfig breakerConfig;
    private final BulkheadConfig bulkheadConfig;
    private final RetryConfig retryConfig;

    /**
     * @param failureRatePercent  share of failed calls that opens the circuit
     * @param minimumCalls        calls needed before the failure rate counts
     * @param openFor             how long the circuit stays open before trying again
     * @param maxConcurrent       calls allowed at once per service
     * @param retryAttempts       attempts for safe reads, including the first
     * @param retryBackoff        wait before the first retry; doubles each time
     */
    public Resilience(float failureRatePercent, int minimumCalls, Duration openFor, int maxConcurrent, int retryAttempts, Duration retryBackoff) {
        this.breakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRatePercent).minimumNumberOfCalls(minimumCalls).slidingWindowSize(Math.max(minimumCalls * 2, 10))
                .waitDurationInOpenState(openFor).permittedNumberOfCallsInHalfOpenState(3).automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordException(Resilience::isServiceFault).build();
        this.bulkheadConfig = BulkheadConfig.custom().maxConcurrentCalls(maxConcurrent).maxWaitDuration(Duration.ofMillis(200)).build();
        this.retryConfig = RetryConfig.custom().maxAttempts(retryAttempts).intervalFunction(a -> retryBackoff.toMillis() * (1L << (a - 1)))
                .retryOnException(Resilience::isServiceFault).build();
    }

    /** A failure of the service or the network, as opposed to a rejected request. */
    static boolean isServiceFault(Throwable t) {
        if (t instanceof HttpClientErrorException) return false;
        return t instanceof HttpServerErrorException || t instanceof ResourceAccessException;
    }

    /**
     * Runs a call to a service under its bulkhead and circuit breaker.
     *
     * @param service  the target service name
     * @param safeRead true when the call can be repeated without side effects, which enables retries
     * @param call     the call
     * @throws ServiceUnavailableException when the circuit is open or the service is at capacity
     */
    public <T> T call(String service, boolean safeRead, Supplier<T> call) {
        CircuitBreaker breaker = breakers.computeIfAbsent(service, s -> CircuitBreaker.of(s, breakerConfig));
        Bulkhead bulkhead = bulkheads.computeIfAbsent(service, s -> Bulkhead.of(s, bulkheadConfig));
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker, call);
        if (safeRead) guarded = Retry.decorateSupplier(Retry.of(service, retryConfig), guarded);
        guarded = Bulkhead.decorateSupplier(bulkhead, guarded);
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            throw new ServiceUnavailableException(service, "circuit open");
        } catch (BulkheadFullException e) {
            throw new ServiceUnavailableException(service, "too many concurrent calls");
        }
    }

    /**
     * Returns the circuit state of a service ({@code CLOSED}, {@code OPEN} or {@code HALF_OPEN}), or {@code CLOSED} when it has not been called yet.
     */
    public String state(String service) {
        CircuitBreaker b = breakers.get(service);
        return b == null ? "CLOSED" : b.getState().name();
    }
}
