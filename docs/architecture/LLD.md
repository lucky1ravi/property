# Low-level design (LLD)

## Layering inside a service

```mermaid
flowchart TB
  REQ[HTTP request] --> AF[AuthFilter: JWT, suspension check]
  AF --> FG[FeatureGateFilter: plan / entitlement]
  FG --> PI[PermissionInterceptor: @RequiresPermission FEATURE:ACTION]
  PI --> C[web/ controller: parse, delegate, shape response]
  C --> SV[service/ business rules]
  SV --> ST[store/ SQL via Db helper]
  SV --> EP[EventPublisher -> platform.event_log, same transaction]
  ST --> TX[RlsTransactionManager: sets org + role per transaction]
  TX --> PG[(PostgreSQL, FORCE RLS via platform.rls_ok)]
```

## Request-time security sequence

```mermaid
sequenceDiagram
  participant B as Browser
  participant G as Gateway (EdgeSecurityFilter)
  participant S as Service
  participant D as PostgreSQL
  B->>G: POST /api/leasing/... Bearer JWT
  G->>G: verify JWT, rate-limit (user / IP), add X-Correlation-Id, security headers
  G->>S: forward (same JWT)
  S->>S: AuthFilter re-verifies JWT, rejects suspended company (403 ORG_SUSPENDED)
  S->>S: FeatureGateFilter, PermissionInterceptor
  S->>D: BEGIN; set org/role; SET LOCAL ROLE propertyos_app
  D-->>S: only rows passing rls_ok(org_id)
  S-->>B: JSON or application/problem+json
```

## Event flow (transactional outbox)

```mermaid
sequenceDiagram
  participant A as Service A
  participant L as platform.event_log
  participant P as EventPoller (service B)
  participant H as EventHandler (idempotent)
  participant DL as platform.dead_letter
  A->>L: INSERT event in the same transaction as the change
  loop every poll
    P->>L: read after consumer_offset
    P->>H: handle(event)
    alt handler fails
      P->>DL: record event, advance offset
    else ok
      P->>P: advance consumer_offset
    end
  end
```

Example: `OrganizationOnboarded` (identity) -> `OnboardingHandler` (workflow) seeds the new company's default automation rules.

## Data model by service (Flyway-managed)

| Service | Schema | Migrations | Main tables |
| --- | --- | --- | --- |
| identity | `identity` | V1-V10 | organization, app_user, role, role_permission, plan, product_default, org_config, audit |
| leasing | `leasing` | V1-V3 | owner, property, unit, listing, showing, application, lease (soft-archive via `archived_at`) |
| billing | `billing` | V1-V3 | charge, invoice, payment, statement, tax form |
| maintenance | `maintenance` | V1 | work order, vendor, inspection, recurring task |
| workflow | `workflow` | V1-V2 | approval, task, notification, automation rule |
| ai | `ai` | V1 | ai_audit |
| document | `document` | V1-V4 | document, version, requirement, signature |
| shared | `platform` | - | event_log, consumer_offset, dead_letter, audit_event, `rls_ok()` |

## Configuration resolution

```mermaid
flowchart LR
  D[defaults.json<br/>shipped] --> PD[product_default<br/>operator, allow-listed sections] --> CO[company overrides] --> PL[plan gating<br/>applyPlan] --> OUT[effective config<br/>branding falls back to company name]
```

## Access model

Permissions are `FEATURE:ACTION` pairs from `FeatureCatalog` (e.g. `OWNER:CREATE`, `PROPERTY:DELETE`, `PLATFORM:CONFIGURE`). `DefaultRoles` holds role templates (Org admin, Broker, Leasing Agent, Accountant, Owner, ...). Company admins can edit roles; new catalog features are synced into existing system roles once (`catalog_sync` markers).

## Account lifecycle

```mermaid
stateDiagram-v2
  [*] --> TempPassword: admin creates user (password shown once, stored hashed)
  TempPassword --> Active: first login forces password change (403 PASSWORD_CHANGE_REQUIRED)
  Active --> TempPassword: admin resets password
  state Company {
    [*] --> ACTIVE
    ACTIVE --> SUSPENDED: operator suspends (reason required, audited)
    SUSPENDED --> ACTIVE: restore
  }
```

Suspension blocks sign-in and every API call for the company's users within ~10 s (each service refreshes the suspended-org list), hides its public listings and pauses its scheduled alert jobs. Platform operators are never blocked.
