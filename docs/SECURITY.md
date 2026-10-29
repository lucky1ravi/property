# Security

## Request path

1. **Gateway `AuthFilter`** validates the JWT. Paths containing `/public/` are open (rental site, applicant upload links, OpenAPI JSON). Paths under `/internal/**` need `X-Internal-Token` and are never routed from outside.
2. **`FeatureGateFilter`** checks the company's plan and entitlements. A disabled feature returns `FEATURE_OFF`. Platform administrators are blocked from company data (`PLATFORM_NO_COMPANY_DATA`); they manage plans and companies only.
3. **`PermissionInterceptor`** enforces `@RequiresPermission("FEATURE:ACTION")` on each endpoint from the user's role.
4. **PostgreSQL row-level security** is the last line of defence: even a bug in application code cannot return another company's rows.

## Tenant isolation with RLS

Every tenant table has a policy calling `platform.rls_ok(org_id, ...)`. `RlsTransactionManager.doBegin` writes the caller's company and role into per-transaction session settings, and when the connection user is a superuser it also runs `SET LOCAL ROLE propertyos_app` so policies apply.

Fail-closed rules:

* `Db.requireTransaction()` throws if role switching is enabled and a query runs outside a transaction (a read outside a transaction would bypass RLS on a superuser connection). Read methods are annotated `@Transactional(readOnly = true)`.
* An integration test (`TenantIsolationIT`, `PhotoReviewIT`) proves a second company gets 404 for the first company's records. This test found and closed a real gap in `MediaService`.

## Secrets and configuration

No secret is in the repository. `JWT_SECRET`, `INTERNAL_TOKEN`, database and AI keys come from environment variables. `.env.local` is git-ignored; `.env.local.example` lists names only.

## Files and documents

Uploads are checked for type and size, stored with a content hash, and served with `X-Content-Type-Options: nosniff`, a restrictive `Content-Security-Policy` and `Cache-Control: private, no-store`. Applicant uploads use signed, expiring links limited to one application.

## Audit

Security-relevant actions write to `platform.audit_event`. AI requests are recorded separately (see [AI.md](AI.md)).
