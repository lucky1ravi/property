# Technical design

How the interesting parts work, and where to read the code.

## Multi-tenancy and row-level security
`RlsTransactionManager` sets `app.org_id`/role as transaction-local settings and (for superuser connections) `SET LOCAL ROLE propertyos_app`. Policies call `platform.rls_ok(org_id, ...)` and are FORCEd. System jobs use `UserContextHolder.runAsSystem`. `Db.requireTransaction()` refuses queries outside a transaction so RLS can never be bypassed silently.

## Authentication
JWT (12 h) signed by `JwtService`; the same `AuthFilter` runs in the gateway and in every service (zero trust). Passwords: PBKDF2-HMAC-SHA256, 120k iterations, random salt. Company-created users get a one-time temporary password (shown once, stored hashed); first sign-in returns `403 PASSWORD_CHANGE_REQUIRED` until changed. Admins can reset a password the same way.

## Authorization
`FeatureCatalog` defines features and actions; `DefaultRoles` holds role templates; `@RequiresPermission` guards endpoints; `FeatureGateFilter` applies the company's plan. Per-role monetary limits (e.g. broker repairs up to $1,000) are carried in the token.

## Configuration
`ConfigService` resolves defaults.json -> `identity.product_default` (operator, allow-listed sections) -> company overrides -> plan gating. `ConfigClient` caches in each service. The admin UI renders the editor from the same structure, so a new setting needs no new screen code.

## Company lifecycle
Onboarding creates the org, admin user, roles, and publishes `OrganizationOnboarded`; workflow seeds the default automation rules. Suspension (`PlatformController.updateOrg`) records reason/actor/time, writes audit, and updates `SuspendedOrgs`, which every service refreshes every 10 s from identity.

## Eventing
`EventPublisher.publish(orgId, type, aggregateType, id, payload)` inserts into `platform.event_log` in the caller's transaction. Each service's `EventPoller` feeds `EventHandler` beans; offsets in `consumer_offset`; failures in `dead_letter`.

## AI
`Llm` interface with `OpenAiLlm`, `BedrockLlm` and an offline rules path; `ModelRunner` is the single choke point (enabled check, call, audit). `Guardrails`: injection screening, PII redaction, fair-housing screening, rate limits, confirm-before-write. Six studio tools (lease abstract, anomaly scan, statement narrative, resident outlook, photo triage, fair housing) plus a permission-checked assistant.

## Gateway
Spring Cloud Gateway routes `/api/<service>/**`; `EdgeSecurityFilter` blocks `/internal/**`, validates JWTs, rate-limits (600/min per user, 60/min per public client by default, env-tunable), adds `X-Correlation-Id`, `X-RateLimit-Remaining` and security headers; `FallbackController` gives a friendly answer when a service is down; Swagger UI aggregates all seven specs.

## Frontend
Shell + seven remotes (Module Federation); `@propertyos/ui` holds the API client (turns server errors into actionable messages; a 403 `ORG_SUSPENDED` signs the user out with an explanation), session, and shared components. Navigation and buttons are hidden by permission, but the server remains the authority.

## Database change management
Flyway per service, run at start-up, forward-only. Migrations in this release: identity V8 product defaults, V9 profile photo, V10 suspension metadata; leasing V3 archive columns.

## Testing strategy
See [TESTING.md](TESTING.md). Layers: unit (`*Test`), per-service integration against real PostgreSQL (`*IT`, Testcontainers or `IT_JDBC_URL`), system tests that start all eight services as real processes (`platform-system-tests`), `OperationsIT` (probes, metrics, correlation ids, security headers), `ZCoverageGateIT` (fails the build below 90% merged line coverage), Vitest for the frontend, Gatling for performance.
