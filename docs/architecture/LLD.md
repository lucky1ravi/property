# Low-level design (LLD)

How the system really behaves, one mechanism at a time. Read the [HLD](HLD.md) first for the overall shape and the [data model](../DATA-MODEL.md) for the tables.

**Contents:** [layering](#1-layering-inside-a-service) · [request path](#2-the-life-of-a-request) · [events](#3-events-the-transactional-outbox) · [state machines](#4-state-machines) · [scheduled jobs](#5-scheduled-jobs) · [rent cycle](#6-the-nightly-rent-cycle) · [public search](#7-public-homes-search) · [amenities](#8-the-amenity-catalogue) · [configuration](#9-configuration-resolution-and-branding) · [AI](#10-an-ai-request) · [accounts](#11-account-and-company-lifecycle) · [errors](#12-errors) · [front end](#13-front-end-composition)

## 1. Layering inside a service

Every service has the same shape, so a developer can move between them without relearning anything.

```mermaid
flowchart TB
  REQ[HTTP request] --> AF[AuthFilter: verify JWT, refuse a suspended company]
  AF --> FG[FeatureGateFilter: plan and entitlement,<br/>operators limited to operator paths]
  FG --> PI[PermissionInterceptor:<br/>@RequiresPermission FEATURE:ACTION]
  PI --> C["web/ controller<br/>parse, delegate, shape the response"]
  C --> SV["service/ business rules<br/>one class per responsibility"]
  SV --> ST["Db helper: SQL, no rules"]
  SV --> EP[EventPublisher<br/>writes platform.event_log in the same transaction]
  ST --> TX[RlsTransactionManager<br/>org, role and party ids set per transaction]
  TX --> PG[(PostgreSQL<br/>FORCE row-level security)]
```

Rules the layering enforces: controllers contain no business rules and no SQL; services never touch HTTP types; SQL lives behind `Db`, which **refuses to run outside a transaction** so a query can never bypass row-level security. Dependencies are constructor-injected, so a service is unit-testable without Spring.

## 2. The life of a request

```mermaid
sequenceDiagram
  participant B as Browser
  participant CF as CloudFront
  participant G as Gateway (EdgeSecurityFilter)
  participant S as Service
  participant D as PostgreSQL
  B->>CF: POST /api/leasing/leases/{id}/actions/SEND, Bearer JWT
  CF->>G: forward /api/*
  G->>G: verify JWT, rate-limit (user or IP), assign X-Correlation-Id, add security headers
  G->>S: forward (same JWT, same correlation id)
  S->>S: AuthFilter re-verifies the JWT, refuses a suspended company (ORG_SUSPENDED)
  S->>S: FeatureGateFilter, then PermissionInterceptor (needs LEASE EDIT)
  S->>D: BEGIN, set app.org_id and role, SET LOCAL ROLE propertyos_app
  S->>D: UPDATE lease, INSERT event, INSERT audit_event
  D-->>S: only rows the policies allow
  S-->>B: 200 JSON, or application/problem+json
  Note over G,S: Every log line on every hop carries the correlation id
```

The JWT is checked **twice** (gateway and service) on purpose: a service never trusts that it sits behind the gateway (zero trust). Service-to-service calls use `/internal/**` with a separate `X-Internal-Token`, and the gateway never routes `/internal/**` from outside.

### How a service calls another

```mermaid
sequenceDiagram
  participant A as Service A
  participant R as Resilience (per target)
  participant B as Service B (/internal/...)
  A->>R: ServiceClient.get(...)
  alt breaker closed
    R->>B: GET, X-Internal-Token, X-Correlation-Id
    B-->>R: 200
    R-->>A: result
  else breaker open (50% failures over 10+ calls)
    R-->>A: ServiceUnavailableException at once
    A->>A: carry on with a fallback (rules instead of AI, no photo check)
  end
```

Reads retry three times with backoff; **writes are never retried**, so a payment cannot be taken twice. At most 50 concurrent calls per target (bulkhead). See [Microservice standards](../MICROSERVICES.md).

## 3. Events: the transactional outbox

A service that changes state publishes a fact in the **same transaction** as the change, so there is never a change without its event or an event without its change (no dual write).

```mermaid
sequenceDiagram
  participant A as Service A
  participant L as platform.event_log
  participant P as EventPoller (service B)
  participant H as EventHandler (idempotent)
  participant DL as platform.dead_letter
  A->>L: INSERT event (same transaction as the change)
  loop every 1.5 s
    P->>L: read events after this subscriber's consumer_offset
    P->>H: handle(event)
    alt handler throws
      P->>DL: record the event, advance the offset
    else ok
      P->>P: advance consumer_offset
    end
  end
```

Delivery is **at least once**, so handlers are idempotent. A failing handler never blocks the stream: its event goes to `dead_letter` for inspection.

Who listens to what (the handlers in the code):

| Handler | Service | Listens for | Does |
| --- | --- | --- | --- |
| `AutomationService` | workflow | **every** event (`*`) | Evaluates the company's automation rules against the event and runs the matching actions, recording each run and the minutes it saved |
| `NotificationService` | workflow | `NotificationRequested` | Delivers a notification in the product, by email or by SMS, honouring each person's preferences, quiet hours and de-duplication, and records a `delivery` |
| `OnboardingHandler` | workflow | `OrganizationOnboarded` | Seeds the new company's default automation rules |
| `BillingEvents` | billing | `LeaseActivated`, `LeaseEnded`, `WorkOrderCompleted` | Opens the lease's deposit record; marks the deposit refund due by the state's deadline when a lease ends; turns a completed work order's vendor cost into an owner expense |
| `LeaseDocuments` | document | `LeaseSignatureUpdated`, `LeaseActivated`, `LeaseRenewed`, `LeaseEnded` | Files the lease agreement in the library and keeps its e-signature status in step with leasing (with a five-minute reconcile in case an event is delayed) |
| `ConfigCacheInvalidator` | every service | `ConfigurationChanged` | Drops that company's cached configuration so a setting change takes effect within seconds |
| `*Seeder` | every service | `DemoResetRequested` | Clears and reseeds the demo company (demo mode only) |

Events published, by service: identity (`OrganizationOnboarded`, `UserCreated`, `RolePermissionsChanged`, `ConfigurationChanged`, `DemoResetRequested`); leasing (`ListingCreated`, `ListingPublished`, `ListingLeased`, `LeadResponded`, `ApplicationDecided`, `LeaseActivated`, `LeaseRenewed`, `LeaseEnded`, `LeaseSignatureUpdated`); billing (`PaymentReceived`, `PaymentFailed`, `LateFeeCharged`, `DepositSettled`, `StatementPublished`); maintenance (`WorkOrderCreated`, `WorkOrderApproved`, `WorkOrderAssigned`, `WorkOrderCompleted`, `SlaBreached`); document (`DocumentUploaded`, `SignatureRequested`, `DocumentSigned`); any service (`NotificationRequested`, `ReminderSent`).

## 4. State machines

The lifecycle of each business object is **data**, not code: JSON definitions in `platform-common/src/main/resources/workflows/` (`LISTING`, `APPLICATION`, `LEASE`, `SHOWING`, `WORK_ORDER`), loaded into each company's `workflow_def` and editable per company in the workflow designer. Each transition names an *action* and the *permission* needed to take it; the API exposes the actions available *now* for a record, and the screens draw buttons from that list, so the UI can never offer a move the server would refuse.

### Listing

```mermaid
stateDiagram-v2
  [*] --> DRAFT
  DRAFT --> AI_REVIEW: SUBMIT (needs LISTING EDIT)
  AI_REVIEW --> LIVE: AI_PASS
  AI_REVIEW --> FLAGGED: AI_FLAG (fair-housing or integrity finding)
  FLAGGED --> LIVE: OVERRIDE (needs LISTING APPROVE)
  FLAGGED --> DRAFT: EDIT
  LIVE --> PAUSED: PAUSE
  PAUSED --> LIVE: RESUME
  LIVE --> DRAFT: EDIT
  PAUSED --> DRAFT: EDIT
  LIVE --> LEASED: MARK_LEASED
  PAUSED --> LEASED: MARK_LEASED
  LEASED --> [*]
```

Only `LIVE` listings of companies that are not suspended appear on the public site.

### Application

```mermaid
stateDiagram-v2
  [*] --> SUBMITTED
  SUBMITTED --> DOCS_PENDING: REQUEST_DOCS
  SCREENED --> DOCS_PENDING: REQUEST_DOCS
  SUBMITTED --> SCREENED: SCREEN_COMPLETE
  DOCS_PENDING --> SCREENED: SCREEN_COMPLETE
  SCREENED --> OWNER_REVIEW: SEND_TO_OWNER
  SCREENED --> APPROVED: APPROVE (needs APPLICATION APPROVE)
  OWNER_REVIEW --> APPROVED: APPROVE
  SUBMITTED --> REJECTED: REJECT
  DOCS_PENDING --> REJECTED: REJECT
  SCREENED --> REJECTED: REJECT
  OWNER_REVIEW --> REJECTED: REJECT
  SUBMITTED --> WITHDRAWN: WITHDRAW
  DOCS_PENDING --> WITHDRAWN: WITHDRAW
  SCREENED --> WITHDRAWN: WITHDRAW
  OWNER_REVIEW --> WITHDRAWN: WITHDRAW
  APPROVED --> [*]
  REJECTED --> [*]
  WITHDRAWN --> [*]
```

A rejection triggers an adverse-action notice when a decision relied on a consumer report. An approved application becomes a lease from `POST /api/leasing/applications/{id}/lease`.

### Lease

```mermaid
stateDiagram-v2
  [*] --> DRAFT
  DRAFT --> SENT: SEND (needs LEASE EDIT)
  SENT --> TENANT_SIGNED: TENANT_SIGN (needs LEASE SIGN)
  TENANT_SIGNED --> ACTIVE: COUNTERSIGN (needs LEASE APPROVE)
  ACTIVE --> RENEWAL_OFFERED: OFFER_RENEWAL
  RENEWAL_OFFERED --> ACTIVE: ACCEPT_RENEWAL
  RENEWAL_OFFERED --> NOTICE_GIVEN: DECLINE_RENEWAL
  ACTIVE --> NOTICE_GIVEN: GIVE_NOTICE
  NOTICE_GIVEN --> ENDED: END
  ACTIVE --> ENDED: END
  ENDED --> CLOSED: SETTLE_DEPOSIT (needs PAYMENT APPROVE)
  DRAFT --> VOID: VOID
  SENT --> VOID: VOID
  CLOSED --> [*]
  VOID --> [*]
```

### Showing

```mermaid
stateDiagram-v2
  [*] --> REQUESTED
  REQUESTED --> CONFIRMED: CONFIRM (needs SHOWING EDIT)
  CONFIRMED --> ATTENDED: ATTEND
  REQUESTED --> NO_SHOW: NO_SHOW
  CONFIRMED --> NO_SHOW: NO_SHOW
  REQUESTED --> CANCELLED: CANCEL
  CONFIRMED --> CANCELLED: CANCEL
```

### Work order

```mermaid
stateDiagram-v2
  [*] --> SUBMITTED
  SUBMITTED --> TRIAGED: TRIAGE (AI or staff)
  TRIAGED --> AWAITING_APPROVAL: REQUEST_APPROVAL (cost above the approver's limit)
  AWAITING_APPROVAL --> TRIAGED: APPROVE (needs WORK_ORDER APPROVE)
  SUBMITTED --> REJECTED: DECLINE
  TRIAGED --> REJECTED: DECLINE
  AWAITING_APPROVAL --> REJECTED: DECLINE
  TRIAGED --> ASSIGNED: ASSIGN (vendor)
  ASSIGNED --> SCHEDULED: SCHEDULE (vendor or staff)
  ASSIGNED --> IN_PROGRESS: START
  SCHEDULED --> IN_PROGRESS: START
  SCHEDULED --> COMPLETED: COMPLETE
  IN_PROGRESS --> COMPLETED: COMPLETE
  COMPLETED --> CLOSED: VERIFY (needs WORK_ORDER VERIFY)
  COMPLETED --> ASSIGNED: REOPEN
  CLOSED --> [*]
  REJECTED --> [*]
```

Approval limits are per role (broker $1,000, property manager $5,000, the owner's own limit) and configured under `approvals`.

### Money objects

```mermaid
stateDiagram-v2
  direction LR
  state "Invoice" as I {
    [*] --> OPEN
    OPEN --> PARTIAL: part paid
    PARTIAL --> PAID: remainder paid
    OPEN --> PAID: paid in full
    OPEN --> VOID: voided with a reason
    PARTIAL --> VOID
  }
  state "Deposit" as D {
    [*] --> PENDING
    PENDING --> HELD: received
    HELD --> REFUND_DUE: lease ended
    REFUND_DUE --> SETTLED: refund and deductions recorded
  }
  state "Owner statement" as O {
    [*] --> DRAFT
    DRAFT --> PUBLISHED: published
    PUBLISHED --> PAID: payout made
  }
```

## 5. Scheduled jobs

Times are `America/Chicago`. Each job runs inside the service that owns its data, and a company-level lock stops two instances running the same job for the same company at once.

| Job | Service | When | What it does |
| --- | --- | --- | --- |
| Event poller | every service | every 1.5 s | Delivers new events to that service's handlers |
| Suspended-company refresh | every service | every 10 s | Reloads the suspended-company list, so a suspension reaches every service within about 10 s |
| **Rent cycle** (`BillingCycleJob`) | billing | 02:00 daily | Creates the period's invoices, applies late fees, takes autopay; one live invoice per lease and period is guaranteed by a unique index |
| **Subscription billing** (`PlatformBilling`) | identity | 02:30 daily | Raises each company's monthly PropertyOS invoice (units times plan price, never below the plan minimum) when automatic billing is on |
| Billing alerts | billing | 07:05 daily | Reminders before rent is due, overdue notices, failed-payment alerts |
| Lease alerts | leasing | 07:15 daily | Lease expiry, renewal offers, deposit refund deadlines |
| Vendor alerts | maintenance | 07:25 daily | Vendor insurance expiry, service-level breaches |
| Document alerts | document | 07:35 daily | Document expiry, signature reminders, missing W-9 |
| Lease document reconcile | document | every 5 min | Makes sure each lease has its documents and signature requests |
| Document seeder | document | every 30 s until ready | Demo-mode seeding |

Jobs for a **suspended** company are skipped. The rent-cycle and subscription-billing crons can be changed with `propertyos.billing.cycle-cron` and `propertyos.platform-billing.cron`; automatic subscription billing is switched by `PROPERTYOS_PLATFORM_BILLING_AUTO` (the AWS demo runs with it on).

## 6. The nightly rent cycle

```mermaid
sequenceDiagram
  participant J as BillingCycleJob 02:00
  participant LK as Per-company lock
  participant LS as leasing /internal
  participant DB as billing schema
  participant EV as event_log
  J->>J: for each active company
  J->>LK: try lock (skip if another instance holds it)
  J->>LS: active leases for this company
  loop each lease whose period is due to be invoiced
    J->>DB: INSERT RENT invoice for the period
    Note over DB: unique index ux_invoice_rent_period refuses a duplicate
    alt autopay enabled and a method on file
      J->>DB: INSERT payment (AUTOPAY), allocate to the invoice
      J->>EV: PaymentReceived or PaymentFailed
    end
  end
  J->>DB: LATE_FEE invoices where overdue, capped by the state rule (stateRules)
  J->>EV: LateFeeCharged
```

Late fees are capped by the property's state rule (`enforceStateFeeCaps`); convenience fees are blocked in states that forbid them. A payment records how it was split across invoices in `allocations`.

## 7. Public homes search

Visitors are not signed in, so the service runs the query **as the system** with explicit filters instead of relying on row-level security: only `LIVE` listings of companies that are **not suspended**.

```mermaid
sequenceDiagram
  participant V as Visitor
  participant SPA as /homes (PublicSearch)
  participant GW as Gateway (open path)
  participant PC as PublicCatalog
  participant DB as leasing schema
  V->>SPA: choose filters, view, sort, page
  SPA->>SPA: keep every choice in the address (shareable, back button works)
  SPA->>GW: GET /api/leasing/public/properties?keyword=&city=&beds=&minRent=...&amenities=A,B&page=1&size=12&sort=min_rent&dir=asc
  GW->>PC: forward
  PC->>PC: validate each filter (400 with a reason when wrong)
  PC->>DB: one query: live listings joined to property and unit, grouped by property
  PC->>DB: second query: the homes inside the properties on this page
  PC->>PC: add company name, build a photo gallery, strip internal ids
  PC-->>SPA: { rows, total, page, size }
  SPA-->>V: cards with carousels, count, pager
```

| Endpoint | Returns |
| --- | --- |
| `GET /public/properties` | One row per property with at least one matching available home: price range, bed and size ranges, next availability, rating, amenities, photos and the matching `units` inside it |
| `GET /public/listings` | One row per home, with the same filters |
| `GET /public/facets` | The choices worth offering: cities with counts, states, property types, bedroom counts, **amenities with their icon and how many homes have each**, rent and size ranges |
| `GET /public/listings/{id}` | One home in full, plus `company` (name, logo, phone, email, website), `other_homes` in the same building and `amenity_icons` |

Filters: `keyword` (property, street, city or ZIP), `city`, `state`, `type`, `beds`, `baths`, `minRent`, `maxRent`, `minSqft`, `maxSqft`, `availableBy`, `amenities` (comma separated, **a home must have all of them**). Without `page` the list is a plain array for older callers; with it, `{rows, total, page, size}`. Sort columns are validated against what the query returns, so a request cannot sort or filter on anything else. `%` and `_` in the keyword are searched literally. The internal company reference is removed from every public row.

## 8. The amenity catalogue

```mermaid
sequenceDiagram
  participant U as Staff member
  participant UI as Property form (AmenityPicker)
  participant API as /api/leasing/amenities
  participant DB as leasing.amenity
  U->>UI: open the property form
  UI->>API: GET (platform entries plus this company's, active only)
  API->>DB: SELECT ... (row-level security: platform rows and own rows)
  U->>UI: type "dog", pick an entry, or choose Add a new amenity
  UI->>API: POST {name, icon}
  API->>API: name 2 to 60 characters, icon from the allowed set, not already a platform or company name (409)
  API->>DB: INSERT, audit AMENITY_ADDED
  UI->>UI: add the new name to the property's amenities
```

Rules: a company may not repeat a name the platform already offers (so the picker never shows two of the same); a company can change only its own entries (the platform's answer 403 *"That amenity belongs to the platform list"*); the platform administrator manages the shared list under `/api/leasing/platform/amenities`; switching an entry off hides it from the picker but leaves properties that have it untouched. Icons come from a fixed set of about fifty drawn by the front end, so a stored icon name always renders; a name typed before the catalogue existed gets an icon guessed from its words.

## 9. Configuration resolution and branding

```mermaid
flowchart LR
  D["defaults.json<br/>shipped with the product"] --> PD["product_default<br/>operator, allow-listed sections"] --> CO["company overrides<br/>org_config, versioned"] --> PL["plan gating<br/>features the plan lacks are off"] --> OUT["effective configuration<br/>cached per service,<br/>refreshed on ConfigurationChanged"]
```

A setting change is validated on save (for example colours must be six-digit hex, a logo must be a PNG or JPEG under 100 KB, contact phone, email and website must look like what they claim to be), stored as a new version with the previous one kept, audited, and announced so every service refreshes within seconds.

**Branding** is the visible end of this chain:

```mermaid
flowchart LR
  CFG[branding.primaryColor] --> S[session API]
  S --> AB["applyBranding()<br/>builds a 50 to 950 colour scale"]
  AB --> V["--federal-* CSS variables"]
  V --> UI[menu, top bar, buttons, links, focus]
  CFG2[branding.logoDataUrl] --> UI2[menu header and invoice PDF]
  CFG3[contactPhone, contactEmail, website] --> PUB[public listing page: Listed by]
```

The default colour is not applied (the stylesheet already has it), so an untouched company looks exactly as shipped. Each micro-frontend has its own data cache, so saving a setting broadcasts a `pos:refresh-session` browser event and the session re-reads at once.

## 10. An AI request

```mermaid
sequenceDiagram
  participant U as User
  participant AIS as ai-service
  participant GR as Guardrails
  participant MR as ModelRunner
  participant LLM as Llm (OpenAI, Bedrock or none)
  participant AU as ai_audit
  U->>AIS: ask the assistant, or run a studio tool
  AIS->>GR: screen for prompt injection, redact PII, fair-housing screen, rate limit
  alt blocked
    GR-->>U: refused with a reason
    GR->>AU: record blocked
  else allowed
    AIS->>MR: enabled? (provider configured and this feature on for the company)
    alt model available
      MR->>LLM: prompt with permission-checked tool results
      LLM-->>MR: answer or a proposed action
    else no model
      MR-->>AIS: deterministic rules answer (fallback = true)
    end
    AIS->>AU: record who, tool, provider, tokens, outcome
    opt a write was proposed
      AIS->>U: pending_action, shown for confirmation
      U->>AIS: confirm
      AIS->>AIS: perform it as the user (their permissions apply)
    end
  end
```

The assistant uses tools that run with **the asking user's permissions**, so it can never see or do more than they can. See [AI](../AI.md).

## 11. Account and company lifecycle

```mermaid
stateDiagram-v2
  [*] --> TempPassword: admin creates a user (password shown once, stored hashed)
  TempPassword --> Active: first sign-in forces a new password (PASSWORD_CHANGE_REQUIRED)
  Active --> TempPassword: admin resets the password
```

Five wrong one-time sign-in codes lock that code (the failed attempts are counted even though the request fails).

```mermaid
stateDiagram-v2
  [*] --> ACTIVE: onboarded by the platform administrator
  ACTIVE --> SUSPENDED: operator suspends (reason required, audited)
  SUSPENDED --> ACTIVE: operator restores
```

A suspension takes effect everywhere within about 10 seconds: sign-in and every API call are refused with `ORG_SUSPENDED`, the company's public listings disappear, and its scheduled jobs are skipped. Platform operators are never given a company's business data.

## 12. Errors

Every failure is `application/problem+json` (RFC 7807) with a stable `code`, built in one place (`GlobalExceptionHandler`) from typed `ApiException` factories.

| Status | Meaning | Example `code` |
| --- | --- | --- |
| 400 | The request is wrong; the `detail` says how | validation messages such as "The lowest rent is above the highest rent." |
| 401 | Not signed in or the token expired | |
| 403 | Signed in but not allowed | `FEATURE_OFF`, `ORG_SUSPENDED`, `PLATFORM_NO_COMPANY_DATA`, `PASSWORD_CHANGE_REQUIRED` |
| 404 | Not found, **also used for another company's record** so existence is never revealed | |
| 409 | Conflicts with the current state | duplicate amenity name, a second live invoice for a period |
| 429 | Rate limit, with `Retry-After` | |
| 503 | A downstream service is unavailable (breaker open), with `Retry-After` | `INTERNAL` for unexpected faults, with a correlation id to quote |

The front end turns these into sentences a person can act on (`friendlyMessage`), and a 403 `ORG_SUSPENDED` signs the person out with an explanation.

## 13. Front-end composition

```mermaid
flowchart TB
  SH["shell: layout, menu, top bar, sign-in,<br/>messages, profile, branding, guided tours"] -->|Module Federation at runtime| R
  subgraph R[Remotes, each built and deployed on its own]
    LE[leasing: portfolio, listings, public homes site]
    PA[payments]
    MA[maintenance]
    DO[documents]
    BR[broker]
    AD[admin: users, configuration, platform console]
    IN[insights: reports]
  end
  UI["@propertyos/ui: design system, API client, session,<br/>tables with server paging, pickers, carousel, amenity components"] -.shared singleton.-> SH & R
```

The menu is drawn once by the shell from one definition (`nav.ts`) filtered by persona, permission and plan feature; pages do not draw their own. List screens use server paging (`usePagedApi` and `DataTable`) so a list of thousands never loads at once; pickers search the server as you type (`EntityPicker`, `MultiEntityPicker`).
