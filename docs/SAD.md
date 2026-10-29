# Solution architecture document (SAD)

## 1. Purpose and scope
PropertyOS lets property-management companies run portfolio, leasing, rent and expenses, maintenance, approvals, documents and AI assistance on one multi-tenant platform, operated by a platform team that onboards companies, sets plans and product defaults, and can suspend a company.

Personas: platform operator, org admin, broker, leasing agent, accountant, owner, resident, vendor, public prospect.

## 2. Architecture views
| View | Where |
| --- | --- |
| Context and building blocks | [HLD](architecture/HLD.md) |
| Internal design, sequences, data | [LLD](architecture/LLD.md) |
| Runtime and delivery | [Deployment](architecture/DEPLOYMENT.md) |
| Security | [SECURITY.md](SECURITY.md) |
| Interfaces | [API index](api/README.md), Swagger UI |
| Decisions | [ADRs](adr/) |

## 3. Constraints and assumptions
* Java 21, Spring Boot 3.4, PostgreSQL 16, React 18 + Vite Module Federation.
* Demo-grade AWS footprint (single-AZ database, no NAT); every production-hardening step is a variable or a small Terraform change, listed in section 8.
* The AI layer is optional: with no provider configured, features fall back to deterministic rules.

## 4. Quality-attribute scenarios
| Scenario | Response | Evidence |
| --- | --- | --- |
| A bug in a controller queries without a company filter | Database returns only the caller's company rows | `TenantIsolationIT`, FORCE RLS policies |
| A user without `PROPERTY:DELETE` calls delete | 403 before the method runs | `PermissionInterceptor`, journey test |
| A company is suspended | Its users are refused within ~10 s everywhere; public listings vanish; alert jobs stop | `PlatformGovernanceIT`, `NewCompanyJourneyIT` |
| A downstream service is slow | Circuit-breaking `ServiceClient` fails fast with a friendly error | ADR 1 |
| A handler throws on an event | Event goes to `dead_letter`; the stream keeps moving | `EventPoller` |
| A bad release is pushed | Smoke check fails the pipeline; redeploy previous image | `deploy.yml`, `deploy-service.sh` |
| A service runs hot | CloudWatch alarm -> SNS -> email | `monitoring.tf` |
| An LLM is unavailable or abused | Rules fallback; prompt-injection screening, PII redaction, rate limits, audit | `AI.md` |

## 5. Data
One PostgreSQL database, a schema per service, Flyway migrations per service (24 in total), shared `platform` schema for events, audit and RLS helpers. Documents are stored in S3 with content hashes. Soft-archive (`archived_at`) for owners, properties and units; vendors are deactivated; only draft listings are hard-deleted.

## 6. Integration
Synchronous: REST through the gateway; service-to-service via `ServiceClient` on `/internal/**` with `X-Internal-Token`. Asynchronous: transactional outbox and per-service pollers.

## 7. Operations
Probes (liveness/readiness), Actuator metrics, correlation id in every log line (`%5p [%X{correlationId}]`), CloudWatch logs and alarms, SNS notification, Gatling performance suites, GitHub Actions deploy with smoke check.

## 8. Known gaps and next steps (honest list)
* Move Fargate tasks to private subnets with VPC endpoints; enable RDS Multi-AZ and automated backups review.
* Many request bodies are still `Map<String,Object>`; typed DTOs would give full Swagger schemas (see [API.md](API.md)).
* Automations (`EventPoller`) are not paused for suspended companies.
* Bedrock provider is implemented but not wired in the AWS demo.
* Restore of archived records exists as an API but has no UI yet.
* No browser-level end-to-end suite; UI is covered by type checks, Vitest and the API journey tests.
