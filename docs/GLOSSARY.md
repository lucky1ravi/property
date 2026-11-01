# Glossary

The words PropertyOS and these documents use, in alphabetical order.

| Term | Meaning |
| --- | --- |
| **Action** | A named move in a state machine, such as `SUBMIT` or `APPROVE`. Each action names the permission needed to take it. |
| **Adverse-action notice** | The notice an applicant must receive when a decision relied on a consumer report. Generated when such an application is rejected. |
| **Amenity** | A feature of a property (Covered parking, Pets welcome). Picked from a catalogue with an icon; a property stores the amenity's name. |
| **Audit event** | A row in `platform.audit_event` recording who changed what, with before and after, written in the same transaction as the change. |
| **Autopay** | A resident's standing instruction to pay rent invoices automatically on the nightly cycle. |
| **Branding** | A company's name, colours, logo and public contact details, applied across the product. |
| **Broker cockpit** | A short, ranked daily queue of what a broker's portfolio needs decided. |
| **Catalogue (public)** | The read-only view of live listings that visitors search, produced by `PublicCatalog`. |
| **Company** | A tenant of PropertyOS: one property management business, with its own users, data and settings. Also called an organization (`org`). |
| **Correlation id** | The `X-Correlation-Id` assigned at the edge and carried through every service and log line of one request. |
| **Dead letter** | An event whose handler failed, kept in `platform.dead_letter` while the event stream moves on. |
| **Delivery** | One email or SMS PropertyOS sent, with its outcome. The operator *Delivery log* lists them, masked. |
| **Entitlement** | A feature a company's plan includes. A feature that is off is hidden and refused by the API. |
| **Event** | A fact that happened (`LeaseActivated`), written to `platform.event_log` in the same transaction as the change. |
| **Facet** | A choice offered in a filter with a count, for example each city and how many homes it has. |
| **Feature** | A module a plan can include (`LEASING`, `PAYMENTS`, `AI_ASSISTANT`), or the first part of a permission (`PROPERTY` in `PROPERTY:EDIT`). |
| **Gateway** | The single front door (`api-gateway`): JWT check, rate limiting, security headers and routing. |
| **Guardrails** | The AI safety layer: prompt-injection screening, PII redaction, fair-housing screening, rate limits and confirm-before-write. |
| **Home** | One available unit as a visitor sees it (a listing). The by-property view groups homes under their building. |
| **Internal endpoint** | An `/internal/**` route for service-to-service calls only, protected by a shared token and never routed from outside. |
| **Lease cycle / rent cycle** | The nightly job that creates each period's rent invoices, applies late fees and takes autopay. |
| **Listing** | A unit advertised for rent, with rent, deposit, photos and a status. Only `LIVE` listings appear publicly. |
| **Micro-frontend** | One independently built and deployed part of the web app (leasing, payments, admin ...), loaded by the shell at runtime. |
| **Operator** | A member of the PropertyOS team who runs the platform. Also called the platform administrator. Cannot see company business data. |
| **Org admin** | A company's administrator: users, roles, configuration. |
| **Outbox** | The pattern of writing an event in the same transaction as the change so the two can never disagree. |
| **Party** | The owner, resident or vendor record a login is linked to (`party_type` and `party_id`). |
| **Permission** | `FEATURE:ACTION`, such as `LEASE:APPROVE`, declared on an endpoint and granted to a role. |
| **Persona** | A kind of user the product is built for: platform administrator, org admin, manager, broker, agent, accountant, owner, resident, vendor, visitor. |
| **Plan** | Starter, Growth or Enterprise: a price per unit, a monthly minimum and a set of features. |
| **Product defaults** | Settings the operator applies to every company unless it overrides them. |
| **Property** | A building or home owned by an owner and managed by a company, containing units. |
| **Resident** | A person with an active lease; signs in as the `TENANT` role. |
| **RLS (row-level security)** | A PostgreSQL feature that makes the database itself return only the rows the caller may see. |
| **Rls_ok** | The function `platform.rls_ok(...)` that every row-level-security policy calls. |
| **Role** | A named set of permissions: Org Admin, Property Manager, Broker, Leasing Agent, Accountant, Owner, Resident, Vendor, Platform Admin. |
| **Scope (data scope)** | Which rows a user's role may reach: the whole company, assigned properties, or only their own records. |
| **Service level (SLA)** | The target response and completion times configured for enquiries and repairs, tracked on each record. |
| **Shell** | The web app that hosts the micro-frontends and draws the menu, top bar and sign-in. |
| **State machine** | A lifecycle defined as states and the actions between them (listing, application, lease, showing, work order). |
| **Studio tools** | The AI tools for specific jobs: lease abstract, anomaly scan, statement narrative, resident outlook, photo triage, fair-housing check. |
| **Suspension** | An operator action that blocks a company's users and hides its public listings, with a mandatory reason. |
| **Unit** | One rentable space in a property, with beds, baths, size and market rent. |
| **Vendor** | A contractor company that works repair jobs; sees only its own jobs. |
| **Work order** | A repair or maintenance job, from a resident's request to a verified completion. |
