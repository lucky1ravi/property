# Architecture

## Services and boundaries

Each service owns its data (its own Postgres schema) and exposes a REST API under `/api/<name>`. No service reads another service's tables. Cross-service reads use `ServiceClient` against `/internal/**` endpoints, which require the `X-Internal-Token` header and are not published in the OpenAPI spec or routed by the gateway.

| Service | Owns |
| --- | --- |
| identity | users, companies, roles and permissions, plans and feature entitlements, configuration |
| leasing | properties, units, listings, showings, applications, leases |
| billing | charges, payments, owner statements, tax forms |
| maintenance | work orders, vendors, inspections |
| workflow | approvals, tasks, notifications |
| ai | assistant, AI studio tools, guardrails, AI audit |
| document | document library, versions, photo review, e-signature, requirements |

The shared `platform` schema holds cross-cutting tables: `event_log`, `consumer_offset`, `dead_letter`, `audit_event`, and the RLS helper functions.

## Layering inside a service

```
web/        controllers: HTTP only (parse, authorise, delegate, shape the response)
service/    business rules; one class per cohesive responsibility
store/ or   SQL via Db / JdbcTemplate; no business rules
repository
```

Controllers never contain business rules and services never touch HTTP types. Permissions are declared with `@RequiresPermission("FEATURE:ACTION")` and enforced by `PermissionInterceptor` before the method runs.

## Events

State changes publish to the `platform.event_log` table in the same transaction as the change (transactional outbox, `EventPublisher`). `EventPoller` in each service delivers events to its `EventHandler` beans (each handler's position is stored in `consumer_offset`, so delivery is at least once) and records an event whose handler fails in `dead_letter` before moving on. Handlers are idempotent.

## Design principles, and where to see them

| Principle | How it is applied | Example |
| --- | --- | --- |
| Single responsibility | A class has one reason to change. Large services are split by responsibility. | `DocumentService` (storage and lifecycle), `DocumentClassifier` (file-name rules), `DocumentRequirements` (checklists and gaps) |
| Open/closed | New behaviour is added by adding a class, not editing a switch. | Each AI studio capability is its own tool: `LeaseAbstractTool`, `AnomalyScanTool`, `FairHousingTool`, and so on. `EventHandler` beans are discovered by Spring. |
| Liskov substitution | Implementations are interchangeable behind an interface. | `Llm` has `OpenAiLlm` and `BedrockLlm` implementations; `LlmProvider` picks one from the environment (or none, for offline rules) and callers cannot tell them apart. |
| Interface segregation | Small, focused interfaces. | `EventHandler` has one method; `ModelRunner` exposes only `enabled`, `ask`, `modelName` to tools. |
| Dependency inversion | High-level code depends on abstractions. | Studio tools depend on `ModelRunner`, not on a vendor client. Controllers depend on service classes injected by constructor. |
| DRY | One implementation of each cross-cutting rule. | `GlobalExceptionHandler` (error shape), `Req` (input parsing), `Words` (OpenAPI wording), `StudioText` (text helpers) |
| Fail closed | When unsure, deny. | `Db.requireTransaction()` refuses to run a query outside a transaction when RLS role switching is on; `AuthFilter` rejects unknown paths without a token |
| Separation of concerns | Policy, mechanism and presentation live apart. | Plan gating in `FeatureGateFilter`, permissions in `PermissionInterceptor`, tenant isolation in the database |
| Explicit errors | Typed, user-readable failures instead of swallowed exceptions. | `ApiException` factories (`badRequest`, `forbidden`, `notFound`, `conflict`) rendered as RFC 7807 problem JSON |
| Constructor injection | Dependencies are explicit and final, which makes classes testable without Spring. | Every service and controller |

## Frontend

A shell app hosts seven remotes through Vite Module Federation, so each business area builds and deploys independently. Shared UI, the API client (`api.ts`) and formatters live in `@propertyos/ui`. The API client translates server errors into messages a person can act on (`friendlyMessage`).
