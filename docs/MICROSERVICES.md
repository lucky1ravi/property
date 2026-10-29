# Microservice standards

How PropertyOS applies the common microservice practices, where each one lives in the code, and how to check it. Anything not yet done is listed at the end rather than claimed.

## At a glance

| Standard | What we do | Where | How to check |
| --- | --- | --- | --- |
| Single entry point (API gateway) | One gateway routes `/api/<service>/**`; `/internal/**` is never routed | `api-gateway` `GatewayApplication`, `EdgeSecurityFilter` | `OperationsIT.internalEndpointsAreNotReachableFromOutside` |
| Database per service | Each service owns a Postgres schema; no cross-service table reads | `docs/ARCHITECTURE.md` | Flyway migrations per service |
| Correlation id and traceable logs | The gateway or the first service creates an `X-Correlation-Id` (a caller's id is kept only if it is 8-64 safe characters). It is on every log line, in the response, and forwarded on every service-to-service call. One access log line per request | `CorrelationFilter`, `ServiceClient`, `EdgeSecurityFilter` | `CorrelationFilterTest`, `ServiceClientTest`, `OperationsIT.aCorrelationIdFollowsTheRequest` |
| Structured logs | Set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (or `logstash`) to get one JSON object per line, including the correlation id, for Loki, ELK or CloudWatch | `common.yml`, `infra/k8s/00-namespace-config.yaml` | Start any service with the variable set |
| Rate limiting | Token bucket per user (600/min) and per IP for public endpoints (120/min) at the gateway; `429` with `Retry-After`. Limits are configurable | `EdgeSecurityFilter` | `EdgeSecurityFilterTest` |
| Circuit breaker | Per target service, opens at 50% failures over 10+ calls, stays open 20 s, then probes. Client errors (4xx) never count. At the gateway each route has its own breaker and answers `503` with `Retry-After` | `Resilience`, `ServiceClient`, `GatewayApplication`, `FallbackController` | `ResilienceTest`, `ServiceClientTest`, `GatewayResilienceTest` |
| Retry with backoff | Reads only (GET), 3 attempts, 150 ms doubling. Writes are never retried automatically, so a payment cannot be taken twice | `Resilience`, gateway retry filter | `ResilienceTest.writesAreNeverRetried` |
| Bulkhead | At most 50 concurrent calls per target service, so a slow service cannot use every thread | `Resilience` | `ResilienceTest.oneServiceCannotUseUnlimitedConcurrency` |
| Timeouts | Connect 3 s; read 8 s, or 90 s for AI. Gateway 5 s connect, 95 s limit | `ServiceClient`, gateway `application.yml` | configuration |
| Graceful degradation | Callers catch `RestClientException`, which includes the fast-fail `ServiceUnavailableException`, and carry on with a fallback (for example, rules instead of AI, no photo check) | `AiClient`, `Lookups`, `MaintenanceService` | `AiScenarioIT` offline paths |
| Health checks | `/actuator/health/liveness` (process only) and `/actuator/health/readiness` (needs its own database). Neighbour health is shown on `/actuator/health/downstream` but is not part of readiness, so one outage cannot remove every instance from rotation | `common.yml`, `DownstreamHealth` | `OperationsIT`, `GatewayResilienceTest` |
| Graceful shutdown | In-flight requests finish (30 s) before a service stops; Kubernetes waits 10 s first so load balancers drain | `common.yml`, `infra/k8s/*.yaml` | rolling deploy with no failed requests |
| High availability | 2+ replicas per service, zero-downtime rolling updates, zone spread, autoscaling on CPU, disruption budgets. Services are stateless; state is in Postgres and events are delivered at least once | `infra/k8s` | `kubectl apply -k infra/k8s` |
| Metrics | Prometheus at `/actuator/prometheus` with request latency histograms, tagged by application | `common.yml` | `OperationsIT.everyServiceReportsLivenessAndReadiness` |
| Security: authentication | JWT checked at the gateway and again in every service (zero trust). Service-to-service calls use a separate internal token | `EdgeSecurityFilter`, `AuthFilter` | `EdgeSecurityFilterTest`, `IdentityScenarioIT` |
| Security: authorisation | Permission per action, field-level rules, and row-level security in the database | `@RequiresPermission`, RLS | `docs/SECURITY.md` |
| Security: hardening | Hardening headers on every response, `Cache-Control: no-store` on API responses, request header and upload size limits, non-root container, dropped Linux capabilities, network policies that deny traffic by default, and a start-up check that **refuses to run production with the built-in development secrets** | `SecurityHeadersFilter`, `SecretsGuard`, `infra/k8s` | `SecretsGuardTest`, `OperationsIT` |
| One-time code brute-force protection | A wrong code now counts toward the 5-attempt lock (previously the count was rolled back with the failed request) | `AuthService.verifyOtp` | `IdentityScenarioIT.signInMethodsWork` |
| Event-driven integration | Transactional outbox; at-least-once delivery; dead-letter table | `EventPublisher`, `EventPoller` | `docs/ARCHITECTURE.md` |
| Configuration outside the code | Everything environment-specific is an environment variable (twelve-factor); secrets come from a Secret | `common.yml`, `infra/k8s/00-namespace-config.yaml` | review |
| API documentation | OpenAPI per service, one Swagger UI at the gateway | `OpenApiConfig` | `docs/API.md` |
| Design principles | SOLID and layering | `docs/ARCHITECTURE.md` | code review |
| Automated quality gates | Unit, integration and whole-system tests; combined line coverage must stay at or above 90% | `platform-system-tests`, CI | `mvn verify` |

## Migrating a legacy application: patterns used here

| Pattern | In PropertyOS |
| --- | --- |
| Strangler fig | The gateway is the single front door. A legacy system can be added as another route and replaced one path at a time, with no change for clients |
| Anti-corruption layer | `ServiceClient` and `Lookups` translate other services' data into this service's own shapes, so a legacy model does not leak inward |
| Database per service | Schemas are separate and each is migrated by its own Flyway scripts |
| Outbox and events | State changes publish events in the same transaction, so there is no dual write |
| Saga and compensation | Multi-step flows run as workflow state machines with reasons, approvals and an audit trail rather than distributed transactions |
| Bulkhead and circuit breaker | See above |
| Observability first | Correlation ids, access logs, metrics and health are in place before more services are split out |

## Not done yet (recommended next)

- **Distributed tracing** (OpenTelemetry spans exported to Jaeger or Tempo). The correlation id gives log-level tracing today; spans would add timing per hop.
- **Idempotency keys** on payment and other create endpoints, so a client can safely retry a write.
- **Service-level rate limits** behind the gateway (defence in depth). Today limits are enforced at the gateway only.
- **Mutual TLS / service mesh** between services; today the internal token and network policies protect internal calls.
- **Secrets manager** integration (Vault, AWS Secrets Manager) instead of Kubernetes Secrets.
- **Contract tests** (consumer-driven) between services.
- **Chaos and load tests** in CI; the Kubernetes manifests have not been run on a real cluster from this environment.
