# ADR 5: Enforce tenant isolation in the database with forced row-level security

Status: accepted

## Context

PropertyOS holds many companies' leases, payments and personal data in one database. The worst failure the system can have is one company seeing another's rows. Application code filters by company everywhere, but a single forgotten `where org_id = ?`, a new report query or an ORM mistake would leak silently.

## Decision

Isolation is enforced **twice, independently**: the application filters, and PostgreSQL refuses.

- Every tenant table carries `org_id` and has `ENABLE` and `FORCE ROW LEVEL SECURITY` with a policy that calls `platform.rls_ok(org_id, property_id, owner_id, tenant_id, vendor_id)`.
- `RlsTransactionManager` writes the caller's company, role and party ids into transaction-local settings at the start of every transaction, and switches to the unprivileged role `propertyos_app` when the connection user is a superuser.
- `Db.requireTransaction()` throws when a query runs outside a transaction, because a read outside one would bypass the policies.
- Owners, residents and vendors are narrowed by the same function: their party id must match `owner_id`, `tenant_id` or `vendor_id`.
- Background jobs run with `UserContextHolder.runAsSystem(orgId, ...)`; the few cross-company operator reads say so explicitly.
- A second company asking for the first company's record gets **404**, never 403, so existence is not revealed.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Database or schema per company | Hundreds of companies means hundreds of schemas to migrate and monitor, and the operator console's cross-company views get hard |
| Application-only filtering | One bug away from a breach; no defence in depth |
| Hibernate or JPA filters | Ties isolation to one access path; raw SQL and reports would bypass it |

## Consequences

- A forgotten filter returns nothing instead of leaking, and `TenantIsolationIT` proves a second company is blind to the first (it found and closed a real gap in photo handling).
- Every new table needs `org_id`, the policy lines and a grant; a migration check and the automatic grant after migrate make that routine.
- Policies add a small per-row cost; indexes lead with `org_id`.
- Operators who must cross companies use an explicit bypass on specific endpoints, which is itself reviewed and tested.
