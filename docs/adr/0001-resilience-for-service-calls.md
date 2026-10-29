# ADR 1: Protect every service-to-service call

Status: accepted

## Context
Services call each other over HTTP. One slow or failing service can otherwise tie up the threads of its callers and spread the outage.

## Decision
All calls go through `ServiceClient`, which applies a bulkhead, a circuit breaker per target service and retries for reads only. When the circuit is open the call fails at once with `ServiceUnavailableException`, a `RestClientException`, so existing fallbacks still work. The gateway adds a circuit breaker and a 503 fallback per route.

## Consequences
- A failing service is isolated within seconds, and recovers on its own after the open period.
- Writes are never retried automatically; callers needing safe retries must use idempotency keys (not yet built).
- The thresholds are configuration (`propertyos.resilience.*`).
