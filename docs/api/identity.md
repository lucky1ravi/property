# Identity service API

Sign-in, users, roles and permissions, companies, plans, configuration, audit, the public website content and the platform operator console.

| | |
| --- | --- |
| Base path | `/api/identity` through the gateway (port 8080) |
| Live contract | `/api/identity/public/v3/api-docs` (OpenAPI 3), browsable in Swagger UI at `/swagger-ui.html` |
| Endpoints | **66** (9 open to visitors, 30 for platform operators) |
| Source | `backend/identity-service` |

> This page is generated from the controllers by `node scripts/generate-api-docs.js`. Do not edit it by hand: change the controller and regenerate. CI fails when it is out of date.

**Reading the table.** *Access* is the `FEATURE:ACTION` permission enforced before the method runs (see [Security](../SECURITY.md)); `signed-in` means any valid user, further limited by ownership checks and row-level security; `open` means no token. `/internal/**` endpoints are service to service only and are not listed.

## Contents

- [audit](#audit) (2)
- [billing](#billing) (4)
- [catalog](#catalog) (1)
- [config](#config) (5)
- [demo](#demo) (1)
- [me](#me) (3)
- [platform](#platform) (30)
- [public](#public) (9)
- [refresh](#refresh) (1)
- [roles](#roles) (5)
- [users](#users) (5)

## audit

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/identity/audit` | `AUDIT:VIEW` | Returns the latest audit entries, optionally for one kind of record. | `IdentityController.recent` |
| GET | `/api/identity/audit/{entityId}` | signed-in | Returns the audit trail of one record, oldest first. | `IdentityController.timeline` |

## billing

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/identity/billing/invoices` | `SETTINGS:VIEW` | &nbsp; | `PlatformBillingController.myInvoices` |
| GET | `/api/identity/billing/invoices/{id}` | `SETTINGS:VIEW` | &nbsp; | `PlatformBillingController.myInvoice` |
| POST | `/api/identity/billing/invoices/{id}/pay` | `SETTINGS:CONFIGURE` | Pays one of the company's own invoices by card or bank transfer. | `PlatformBillingController.pay` |
| GET | `/api/identity/billing/subscription` | `SETTINGS:VIEW` | The company's own plan, price, balance and invoices. | `PlatformBillingController.subscription` |

## catalog

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/identity/catalog` | signed-in | Returns the catalogue of features, actions, scopes and field rules used by the role editor. | `IdentityController.catalog` |

## config

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/identity/config` | signed-in | Returns the effective configuration: the company's changes merged over the defaults. | `IdentityController.config` |
| PUT | `/api/identity/config/{section}` | `SETTINGS:CONFIGURE` | Replaces one configuration section and records it in the history. | `IdentityController.updateConfig` |
| GET | `/api/identity/config/history` | `SETTINGS:VIEW` | Lists recent configuration versions. | `IdentityController.history` |
| GET | `/api/identity/config/overrides` | `SETTINGS:VIEW` | Returns only what the company has changed from the defaults. | `IdentityController.overrides` |
| POST | `/api/identity/config/rollback/{version}` | `SETTINGS:CONFIGURE` | Restores the configuration as it was at an earlier version. | `IdentityController.rollback` |

## demo

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/identity/demo/reset` | `SETTINGS:CONFIGURE` | Resets this organisation's demo data in every service (each service reseeds itself). | `IdentityController.resetDemo` |

## me

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/identity/me` | signed-in | Current user, permissions, data scope, enabled features and branding. | `IdentityController.me` |
| PUT | `/api/identity/me/profile` | signed-in | The signed-in user changes their own name, title, phone or photo. | `IdentityController.updateProfile` |
| PUT | `/api/identity/me/ui-state` | signed-in | Saves what the signed-in person has been shown (welcome and page tours, dismissed hints, the checklist) so it follows them to another device. | `IdentityController.saveUiState` |

## platform

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/identity/platform/billing/invoices` | `PLATFORM:VIEW` | Every company's invoices, paged. | `PlatformBillingController.invoices` |
| POST | `/api/identity/platform/billing/invoices` | `PLATFORM:CONFIGURE` | A one-off charge for a company. | `PlatformBillingController.charge` |
| GET | `/api/identity/platform/billing/invoices/{id}` | `PLATFORM:VIEW` | &nbsp; | `PlatformBillingController.invoice` |
| POST | `/api/identity/platform/billing/invoices/{id}/payments` | `PLATFORM:CONFIGURE` | Records a payment received (wire, cheque, bank, card) or a credit. | `PlatformBillingController.record` |
| POST | `/api/identity/platform/billing/invoices/{id}/void` | `PLATFORM:CONFIGURE` | &nbsp; | `PlatformBillingController.voidInvoice` |
| GET | `/api/identity/platform/billing/orgs/{orgId}` | `PLATFORM:VIEW` | One company's whole account: balance, every invoice and every payment. | `PlatformBillingController.ledger` |
| GET | `/api/identity/platform/billing/payments` | `PLATFORM:VIEW` | &nbsp; | `PlatformBillingController.payments` |
| POST | `/api/identity/platform/billing/run` | `PLATFORM:CONFIGURE` | Makes this month's invoices now (or for the month of asOf). | `PlatformBillingController.run` |
| GET | `/api/identity/platform/billing/summary` | `PLATFORM:VIEW` | Billed, collected, outstanding and overdue, the last six months, the plan mix and who owes the most. | `PlatformBillingController.summary` |
| GET | `/api/identity/platform/defaults` | `PLATFORM:VIEW` | Current product defaults and which sections the operator has changed. | `ProductDefaultsController.view` |
| PUT | `/api/identity/platform/defaults/{section}` | `PLATFORM:CONFIGURE` | Replaces one section for every company that has not set it itself. | `ProductDefaultsController.save` |
| DELETE | `/api/identity/platform/defaults/{section}` | `PLATFORM:CONFIGURE` | Puts a section back to the built-in default. | `ProductDefaultsController.reset` |
| GET | `/api/identity/platform/health` | `PLATFORM:VIEW` | Asks every service whether it is up and how quickly it answered. | `PlatformConsoleController.health` |
| GET | `/api/identity/platform/orgs` | `PLATFORM:VIEW` | Platform administrators: lists customer companies. | `PlatformController.orgs` |
| POST | `/api/identity/platform/orgs` | `PLATFORM:CONFIGURE` | Onboards a new management company with default roles, its first admin and default configuration. | `PlatformController.onboard` |
| GET | `/api/identity/platform/orgs/{id}` | `PLATFORM:VIEW` | Platform administrators: one company's profile. | `PlatformController.orgDetails` |
| PUT | `/api/identity/platform/orgs/{id}` | `PLATFORM:CONFIGURE` | Platform administrators: changes a company's profile, plan or status. | `PlatformController.updateOrg` |
| DELETE | `/api/identity/platform/orgs/{id}` | `PLATFORM:CONFIGURE` | Soft-deletes a company while retaining its records and audit history. | `PlatformController.deleteOrg` |
| GET | `/api/identity/platform/orgs/{id}/features` | `PLATFORM:VIEW` | Platform administrators: a company's feature switches with plan defaults and overrides. | `PlatformController.features` |
| PUT | `/api/identity/platform/orgs/{id}/features` | `PLATFORM:CONFIGURE` | Platform administrators: turns individual features on or off for a company. | `PlatformController.setFeatures` |
| GET | `/api/identity/platform/plans` | `PLATFORM:VIEW` | Every plan with its price, features and how many companies are on it. | `PlanController.list` |
| POST | `/api/identity/platform/plans` | `PLATFORM:CONFIGURE` | Creates a plan. | `PlanController.create` |
| PUT | `/api/identity/platform/plans/{key}` | `PLATFORM:CONFIGURE` | Changes any of a plan's fields. | `PlanController.update` |
| DELETE | `/api/identity/platform/plans/{key}` | `PLATFORM:CONFIGURE` | Deletes a plan no company is on. | `PlanController.delete` |
| GET | `/api/identity/platform/site/contact-requests` | `PLATFORM:VIEW` | Contact-form enquiries, newest first. | `SiteAdminController.contactRequests` |
| PUT | `/api/identity/platform/site/contact-requests/{id}` | `PLATFORM:CONFIGURE` | Marks an enquiry NEW, CONTACTED or CLOSED. | `SiteAdminController.setStatus` |
| GET | `/api/identity/platform/site/features` | `PLATFORM:VIEW` | Every feature as described on the website. | `SiteAdminController.features` |
| PUT | `/api/identity/platform/site/features/{key}` | `PLATFORM:CONFIGURE` | Changes a feature's title, summary or visibility on the website. | `SiteAdminController.updateFeature` |
| GET | `/api/identity/platform/summary` | `PLATFORM:VIEW` | Platform administrators: console figures (companies, users, users active in the last 7 days, plan mix, daily sign-ins and recent configuration changes). | `PlatformController.summary` |
| GET | `/api/identity/platform/usage` | `PLATFORM:VIEW` | One row per company: who is signed up, who really uses it, and whether it is going quiet. | `PlatformConsoleController.usage` |

## public

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/identity/public/change-password` | open | Replaces a temporary (or current) password and signs in. | `AuthController.changePassword` |
| POST | `/api/identity/public/contact` | open | Contact form. | `SiteController.contact` |
| POST | `/api/identity/public/demo-login` | open | Signs in as a demo persona without a password. | `AuthController.demoLogin` |
| GET | `/api/identity/public/demo-users` | open | Persona picker for the demo: one click signs in as each role. | `AuthController.demoUsers` |
| POST | `/api/identity/public/login` | open | Signs in with email and password. | `AuthController.login` |
| GET | `/api/identity/public/options` | open | Tells the sign-in page whether to show demo hints. | `AuthController.options` |
| POST | `/api/identity/public/otp/request` | open | Sends a one-time sign-in code to an email address. | `AuthController.requestOtp` |
| POST | `/api/identity/public/otp/verify` | open | Checks a one-time code and signs the user in. | `AuthController.verifyOtp` |
| GET | `/api/identity/public/site` | open | Plans with prices and the features each includes, plus the feature list, for the marketing pages. | `SiteController.site` |

## refresh

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/identity/refresh` | signed-in | Re-issues a token with the latest roles and permissions (after an admin change). | `IdentityController.refresh` |

## roles

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/identity/roles` | signed-in | Lists the company's roles with their permissions. | `IdentityController.roles` |
| POST | `/api/identity/roles` | `ROLE:CONFIGURE` | Creates a custom role. | `IdentityController.createRole` |
| PUT | `/api/identity/roles/{id}` | `ROLE:CONFIGURE` | Edits a role's name, description, scope, colour or field rules. | `IdentityController.updateRole` |
| DELETE | `/api/identity/roles/{id}` | `ROLE:CONFIGURE` | Deletes a custom role. | `IdentityController.deleteRole` |
| PUT | `/api/identity/roles/{id}/permissions` | `ROLE:CONFIGURE` | Replaces a role's permissions. | `IdentityController.setPermissions` |

## users

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/identity/users` | `USER:VIEW` | Lists the company's users with their roles and properties. | `IdentityController.users` |
| POST | `/api/identity/users` | `USER:CREATE` | Creates a user and assigns roles and properties. | `IdentityController.createUser` |
| PUT | `/api/identity/users/{id}` | `USER:EDIT` | Changes a user's details, status, roles or properties. | `IdentityController.updateUser` |
| DELETE | `/api/identity/users/{id}` | `USER:DELETE` | Deletes a user. | `IdentityController.deleteUser` |
| POST | `/api/identity/users/{id}/reset-password` | `USER:EDIT` | Gives a user a new temporary password, shown once, which they must change at next sign-in. | `IdentityController.resetPassword` |
