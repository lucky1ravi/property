# The engineering journey: from idea to a deployed, tested, operable product

This page is the map of everything around the code: how it is built, proven, shipped, secured and run. Each row points at the file that proves it.

## At a glance

| | |
| --- | --- |
| Backend | 7 business services + API gateway, Java 21, Spring Boot 3.4, 191 production classes, 215 documented endpoints |
| Frontend | React shell + 7 federated micro-frontends (admin, leasing, payments, maintenance, documents, insights, broker) |
| Database | PostgreSQL 16, schema per service, 24 Flyway migrations, forced row-level security |
| Tests | 165+ backend test methods across 21 unit-test and 16 integration/system classes, 90% merged-coverage gate (last full run 92.7-92.8%), Vitest, Gatling |
| Delivery | GitHub Actions CI on every PR, push-to-deploy CD, OIDC to AWS (no stored keys), changed-units-only rollout |
| Infrastructure | Terraform: VPC, ALB, ECS Fargate, RDS, S3, CloudFront, ECR, Secrets Manager, IAM, CloudWatch + SNS (about 1,200 lines in `infra/aws`) |
| Live | https://dtqbmyulhq2jl.cloudfront.net (Mumbai) |

## 1. Product depth
* Full company journey, tested from scratch by `NewCompanyJourneyIT`: operator onboards a company -> admin first sign-in and password change -> creates users (one-time temporary passwords) -> adds owners, properties, units, vendors -> lists a unit -> prospect applies from the public site -> approval -> lease -> rent charges -> work orders -> expenses -> archive -> role-permission checks -> suspend and reactivate.
* Operator console: company onboarding, plans and feature entitlements, **product defaults** that apply to every company, suspension with a required reason and audit trail.
* Company admin: roles and permissions per feature and action (create / edit / delete / view), user management, configuration, branding, audit log.
* Every user has a profile page (name, title, phone, photo; email is fixed).
* Residents, owners and vendors sign in to their own scoped views, linked to their records.

## 2. Configurable product ("config as data")
Defaults -> operator product defaults -> company overrides -> plan gating, across features, fees, invoicing, approvals, screening, SLA, renewals, AI, notifications, branding, state rules, payments, alerts, chat, documents, moderation, templates and custom fields. The admin screens are generated from the config structure; a new setting needs no new UI code. See [CONFIGURATION.md](CONFIGURATION.md).

## 3. Quality
| Layer | What | Where |
| --- | --- | --- |
| Unit | Rules in isolation | `*Test` (surefire) |
| Integration | One Spring context per service on **real PostgreSQL** (Testcontainers, or `IT_JDBC_URL`) | `*IT` (failsafe) |
| System | **All 8 services started as real processes**, driven over HTTP as every persona | `backend/platform-system-tests` |
| Isolation | A second company must get 404 for the first company's data | `TenantIsolationIT` (found and closed a real gap) |
| Operations | Probes, metrics, correlation ids, security headers | `OperationsIT` |
| Coverage gate | Build fails below 90% line coverage merged across every layer (JaCoCo agent in each process) | `ZCoverageGateIT` |
| Frontend | Type-check on build, Vitest | `frontend` |
| Performance | Smoke, load, stress, spike, soak (Gatling) with pass/fail service levels (error rate, p95) | `performance/`, `performance.yml` |
Details: [TESTING.md](TESTING.md), [performance/README.md](../performance/README.md).

## 4. True CI/CD
* **CI** (`ci.yml`): on every PR: work out what changed, run backend `mvn verify` (all test layers + coverage gate), frontend tests and builds for changed apps, publish coverage and test reports.
* **CD** (`deploy.yml`): push to `develop` -> `changes.sh` diffs against the `deployed/<unit>` tags -> test and build only the changed services -> deploy identity, then workers, then gateway -> build and upload only changed frontend apps and invalidate only their CloudFront paths -> smoke check through CloudFront -> move tags.
* **No secrets**: GitHub OIDC assumes an AWS role per run.
* One deployment at a time; queued runs diff from the last *successful* deploy.
Details: [CI-CD.md](CI-CD.md), [architecture/DEPLOYMENT.md](architecture/DEPLOYMENT.md).

## 5. Infrastructure as code
Everything on AWS is Terraform (`infra/aws`): network, ALB, ECS services, ECR (one repo per service, lifecycle policy), RDS, S3 (encrypted, versioned, public access blocked), CloudFront, Secrets Manager, least-privilege IAM, monitoring. Also Docker Compose for local and all-in-one, and Kubernetes manifests. See [AWS-DEPLOY.md](AWS-DEPLOY.md).

## 6. Security
Defence in depth on every request: gateway JWT + rate limit + security headers -> per-service zero-trust JWT check -> plan gate -> per-endpoint permission -> forced row-level security. PBKDF2 passwords, one-time temporary passwords, forced change on first sign-in, company suspension kill-switch, audit events, signed expiring upload links, `/internal/**` never routed externally, no secrets in git. See [SECURITY.md](SECURITY.md) and [SAD](SAD.md).

## 7. Database automation
Flyway per service, applied at start-up by the rollout that needs it; forward-only, additive migrations; seeders for demo data and for one-time catalog syncs (`catalog_sync` markers) so new permissions reach existing roles safely.

## 8. API documentation
springdoc OpenAPI per service; gateway Swagger UI with all seven specs; `OperationDocumenter` generates summaries, tags, required permission and error responses from the code. Static inventory per service in [api/](api/README.md).

## 9. Observability and alerting
Correlation id generated at the gateway and carried through every service and every log line; Actuator liveness/readiness and metrics; CloudWatch logs; alarms on ALB 5xx, gateway health, per-service memory and database CPU, all notifying an SNS topic (email subscription via a variable); ADRs [2](adr/0002-liveness-and-readiness.md) and [3](adr/0003-correlation-id.md).

## 10. Gateway capabilities
Single entry point and routing for seven services, JWT validation, per-user and per-client rate limiting with `X-RateLimit-Remaining`, CORS, `/internal/**` blocking, security headers, correlation ids, friendly fallback responses, aggregated Swagger UI.

## 11. AI features
Assistant with permission-checked tools and confirm-before-write; AI studio with lease abstraction, anomaly scan, owner-statement narrative, resident outlook, photo triage, fair-housing screening; guardrails (prompt-injection screening, PII redaction, rate limits); every call audited; works with no model configured (rules fallback); provider-agnostic (OpenAI and Bedrock implementations). See [AI.md](AI.md).

## 12. Engineering practice
Layered services with one responsibility per class; design principles mapped to real classes in [ARCHITECTURE.md](ARCHITECTURE.md); four ADRs; Javadoc on public types and a `package-info` for 45 packages; constructor injection throughout; RFC 7807 errors; CONTRIBUTING guide.

## Documentation map
[HLD](architecture/HLD.md) - [LLD](architecture/LLD.md) - [Deployment](architecture/DEPLOYMENT.md) - [SAD](SAD.md) - [Technical design](TECHNICAL-DESIGN.md) - [Security](SECURITY.md) - [API](api/README.md) - [Testing](TESTING.md) - [CI/CD](CI-CD.md) - [AWS](AWS-DEPLOY.md) - [AI](AI.md) - [ADRs](adr/)

## Known limitations (stated up front)
See SAD section 8. Notably: demo-grade AWS footprint, some untyped request bodies in Swagger, no browser E2E suite, Bedrock not wired in the demo.
