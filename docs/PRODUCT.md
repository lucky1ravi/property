# Product guide

What PropertyOS does, for whom, and how the pieces fit together. This is the document to read before any technical one.

## 1. The product in one picture

A **company** (a property management business) signs up. Its team runs the whole business inside one workspace. Its **owners**, **residents** and **vendors** each get their own narrow view. Anyone on the internet can browse its available homes. The **PropertyOS team** governs all companies from a separate console and can never open a company's business data.

```mermaid
flowchart TB
  subgraph PLAT[PropertyOS team: platform console]
    PA[Platform administrator]
  end
  subgraph CO[One company: its own isolated workspace]
    direction TB
    STAFF[Org admin, property manager,<br/>broker, leasing agent, accountant]
    OWN[Owners]
    RES[Residents]
    VEN[Vendors]
  end
  PUB[Visitors and applicants<br/>no account needed]
  PA -->|onboards, sets plan, suspends,<br/>never sees leases or payments| CO
  STAFF <--> OWN & RES & VEN
  PUB -->|browse homes, enquire,<br/>request a tour, apply| STAFF
```

## 2. Who uses it

| Persona | Signs in as | Sees | Typical day |
| --- | --- | --- | --- |
| **Platform administrator** | PropertyOS operator | Companies, plans, product defaults, subscription payments, company usage, service health, delivery log, website enquiries, the shared amenity list | Onboard a company, check service health, follow up an overdue subscription, answer a demo request |
| **Org admin** | Company administrator | Everything in the company, plus users and roles, configuration, branding and the company's PropertyOS subscription | Add a teammate, change the late-fee rule, set the company colour and logo |
| **Property manager** | Operations lead | The whole portfolio, approves repairs up to a higher limit | Work the daily queue, approve a repair, review a lease |
| **Broker** | Portfolio manager | An assigned set of properties, the broker cockpit and opportunities | Triage the cockpit, respond to a lead, offer a renewal |
| **Leasing agent** | Leasing team | Listings, leads, showings, applications; cannot approve | Publish a listing, schedule a tour, screen an application |
| **Accountant** | Finance | Receivables, payments, deposits, owner statements, tax and compliance | Chase an overdue invoice, publish owner statements, prepare 1099 data |
| **Owner** | Property owner | Only their own properties, leases, statements and reports | Approve a large repair, read a statement |
| **Resident** | Tenant | Only their own lease, balance, requests and messages | Pay rent, report a repair with a photo, read the lease, rate their home |
| **Vendor** | Contractor | Only jobs assigned to their company | Schedule and complete a job |
| **Visitor** | Nobody | The public homes site and marketing site | Search homes, ask a question, request a tour, apply and upload documents |

The seven company roles are templates (`DefaultRoles`): a company admin can adjust any permission per feature and action. See [Security](SECURITY.md) for how permissions are enforced.

## 3. What each area does

```mermaid
flowchart LR
  L[Listing<br/>goes live] --> E[Enquiry or<br/>tour request]
  E --> A[Application]
  A --> S[Screening]
  S --> D{Decision}
  D -->|approved| LS[Lease drafted,<br/>sent, e-signed]
  D -->|declined| N[Adverse action<br/>notice]
  LS --> R[Active lease]
  R --> C[Monthly rent<br/>cycle]
  C --> P[Payment]
  R --> M[Repair<br/>requests]
  R --> RN[Renewal offer<br/>or move-out]
  RN --> DP[Deposit<br/>settlement]
  P --> ST[Owner<br/>statement]
```

### Portfolio

Owners, properties, units. Each property has a photo gallery (moderated by AI for relevance and quality), a type, year built and **amenities**.

**Amenities are picked, not typed.** PropertyOS keeps a catalogue of amenities, each with an icon: 26 shared ones the platform administrator maintains (Covered parking, Swimming pool, Pets welcome, EV charging...), plus any a company adds for itself ("Dog wash", "Business centre"). On the property form a searchable list shows each with its icon, and anyone who can edit properties can add a missing one on the spot. A property stores amenity *names*, so renaming or removing a catalogue entry never changes an existing property.

```mermaid
flowchart LR
  PA[Platform administrator] -->|keeps| PL[(Shared amenities<br/>26 seeded)]
  CA[Company staff] -->|adds| OW[(Company's own amenities)]
  PL & OW --> PK[Searchable picker<br/>on the property form]
  PK --> PR[Property stores names]
  PR --> V[Visitors see each amenity<br/>with its icon, and can<br/>filter the search by it]
```

### Leasing

* **Listings** are drafted with AI help, screened for fair-housing wording, reviewed, approved and published. A listing moves *Draft, AI review, Live* (or *Flagged* when the review finds a problem, until a person with approval rights overrides it), can be *Paused* and resumed, and ends *Leased*.
* **The public homes site** (`/homes`) is open to anyone. It shows every available home, grouped **by property** (each building once, with its available units inside) or **home by home**, with a photo carousel on every card, the company that lists it, and the resident rating. Visitors filter by words, city, property type, bedrooms, bathrooms, rent range, size range, available-by date and amenities, sort, and page through results. The choices are kept in the address, so a search can be shared. A home's page shows the facts with icons, amenities with icons, costs, the other homes in the same building, and a **Listed by** box with the company's logo, phone, email and website (set under Branding).
* **Leads and showings** come from enquiries and tour requests, answered automatically within the company's service level and followed up by a person.
* **Applications** move *Submitted, (Docs pending), Screened, Owner review, Approved*, or end *Rejected* (with an adverse-action notice) or *Withdrawn*. Applicants upload documents through a private, expiring link.
* **Leases** move *Draft, Sent, Tenant signed, Active* (after the company countersigns), then *Renewal offered* or *Notice given*, *Ended* and *Closed* once the deposit is settled.

### Payments

* A **nightly rent cycle** creates each period's invoices, applies late fees under the state's cap, and takes autopay. It runs with a per-company lock, so an overlapping run can never bill twice.
* **Invoices** have a professional PDF: logo, colour band, amount due, itemised table, tax line, payments and how to pay.
* **Collections**, **payments received**, **deposits** (held, refund due, settled), **owner statements** (draft, published, paid) and **tax and compliance** (1099 data, W-9 tracking).

### Maintenance

A resident reports a repair, with photos. AI triages it (category, urgency). If the cost is above the approver's limit it waits for approval (the owner's limit, then the manager's). It is assigned to a vendor, scheduled, worked and completed; a person then verifies it and it is closed (or reopened and sent back to the vendor), with a service-level clock on every step. Vendors are rated by staff, owners and residents; vendors can see only their own jobs. **Preventive maintenance** plans create work orders on a schedule.

### Documents and e-signature

A library with versions, requirement checklists (what a lease file should contain, what is missing), a photo review queue, and signature requests with reminders.

### Insights and automation

* **Reports**: rent roll, occupancy and vacancy, vacant units, lease expirations, leasing funnel, ratings, who owes what, income and expenses, transactions, payments, fees, repair costs, jobs, vendor scorecard. Every line drills down to the records behind it.
* **Broker cockpit**: a short daily queue ranked by what needs a decision.
* **AI tools**: lease abstract, anomaly scan, owner-statement narrative, resident outlook, photo triage, fair-housing check. See [AI](AI.md).
* **Automation rules**: a library of ready-made rules (acknowledge an enquiry, remind before rent is due, escalate a breached repair deadline) that a company can switch on, edit or extend, each counted toward *time saved*.
* **Deadlines and alerts**: lease expiry, deposit refunds, vendor insurance, tax filing, 1099, missing W-9, document expiry, signature reminders.

### Every company is its own brand

Under *Configuration, Branding* a company sets its name, **primary colour**, **logo**, accent colour and public contact details. The colour reaches the menu, top bar, buttons and links through CSS variables; the logo shows in the menu and on every invoice; the contact details appear on the public listing page.

## 4. Onboarding a company

```mermaid
sequenceDiagram
  participant OP as Platform administrator
  participant ID as identity service
  participant WF as workflow service
  participant AD as Company admin
  OP->>ID: Create company (name, state, plan, admin email)
  ID->>ID: Create organization, roles, admin user with one-time password
  ID-->>WF: OrganizationOnboarded event
  WF->>WF: Seed the company's default automation rules
  ID-->>OP: Temporary password, shown once
  OP->>AD: Pass it on
  AD->>ID: First sign-in
  ID-->>AD: Must choose a new password (PASSWORD_CHANGE_REQUIRED)
  AD->>ID: Add users, owners, properties, units
```

A company's plan (Starter, Growth, Enterprise) decides which features it has; a feature that is off simply does not appear, and the API refuses it with a clear message.

## 5. The platform console

Only platform administrators see these pages, and the system refuses them every company page (`PLATFORM_NO_COMPANY_DATA`).

| Page | Purpose |
| --- | --- |
| **Platform** | Companies: create, change plan, suspend (a reason is required and audited) and restore |
| **Product defaults** | Settings that apply to every company unless it overrides them |
| **Plans** | Plans, prices, minimums and the features each includes |
| **Subscription payments** | What each company pays PropertyOS: monthly invoices (units times plan price, never below the plan minimum), payments, credits, one-off charges, overdue and failed-payment alerts |
| **Company usage** | Units, users and activity per company |
| **Service health** | Every service's health and dependencies at a glance |
| **Delivery log** | Every email and SMS the platform sent, masked by default; opening the full recipient and subject needs a reason that is written to that company's own audit log |
| **Enquiries** | Demo requests and website contact messages |
| **Features** | How each feature is described on the public website, and whether it is shown |
| **Amenities** | The shared amenity list every company starts from |

```mermaid
flowchart LR
  OPN[Operator opens a delivery] --> M[Sees masked recipient<br/>and outcome only]
  M --> Q{Needs the full<br/>message?}
  Q -->|no| DONE[Done]
  Q -->|yes| R[Gives a reason]
  R --> AUD[(Reason, who, when,<br/>written to the company's audit log)]
  AUD --> FULL[Full recipient and subject shown]
```

## 6. Plans and entitlements

| Plan | Price per unit per month | Monthly minimum | Features |
| --- | --- | --- | --- |
| Starter | $1.25 | $125 | Leasing, payments, maintenance, owner portal, documents |
| Growth | $1.75 | $300 | Starter plus vendor portal, automations, AI assistant, renewals, recurring maintenance, messaging, e-signature |
| Enterprise | $2.50 | $1,500 | Everything, including the broker cockpit and opportunities |

A company is billed monthly: units times the plan price, never less than the plan minimum.

These are the shipped defaults (`defaults.json`). Plans and prices are data managed on the Plans page, so the Plans page and the public Plans page are authoritative.

## 7. Where to go next

* How it is built: [HLD](architecture/HLD.md), [LLD](architecture/LLD.md), [Data model](DATA-MODEL.md).
* Every screen exercised end to end: [Browser journeys](../e2e/JOURNEYS.md).
* What changed recently: [Changelog](CHANGELOG.md).
