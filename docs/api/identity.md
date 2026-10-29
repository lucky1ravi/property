# Identity service API

Base path `/api/identity` through the gateway (port 8080). Live, authoritative OpenAPI spec: `/api/identity/public/v3/api-docs`; browse it in Swagger UI at `/swagger-ui.html`.

Generated from the controllers in `backend/identity-service`: **49 endpoints**. *Permission* is the `FEATURE:ACTION` enforced by `PermissionInterceptor`; `signed-in` means any valid user (then limited by ownership checks and database row-level security); `open` means no token. `/internal/**` endpoints are service-to-service only and are not listed.

| Method | Path | Permission | Handler |
| --- | --- | --- | --- |
| GET | `/api/identity/audit` | AUDIT:VIEW | `IdentityController.recent` |
| GET | `/api/identity/audit/{entityId}` | signed-in | `IdentityController.timeline` |
| GET | `/api/identity/catalog` | signed-in | `IdentityController.catalog` |
| GET | `/api/identity/config` | signed-in | `IdentityController.config` |
| GET | `/api/identity/config/history` | SETTINGS:VIEW | `IdentityController.history` |
| GET | `/api/identity/config/overrides` | SETTINGS:VIEW | `IdentityController.overrides` |
| POST | `/api/identity/config/rollback/{version}` | SETTINGS:CONFIGURE | `IdentityController.rollback` |
| PUT | `/api/identity/config/{section}` | SETTINGS:CONFIGURE | `IdentityController.updateConfig` |
| POST | `/api/identity/demo/reset` | SETTINGS:CONFIGURE | `IdentityController.resetDemo` |
| GET | `/api/identity/me` | signed-in | `IdentityController.me` |
| PUT | `/api/identity/me/profile` | signed-in | `IdentityController.updateProfile` |
| GET | `/api/identity/platform/defaults` | PLATFORM:VIEW | `ProductDefaultsController.view` |
| DELETE | `/api/identity/platform/defaults/{section}` | PLATFORM:CONFIGURE | `ProductDefaultsController.reset` |
| PUT | `/api/identity/platform/defaults/{section}` | PLATFORM:CONFIGURE | `ProductDefaultsController.save` |
| GET | `/api/identity/platform/orgs` | PLATFORM:VIEW | `PlatformController.orgs` |
| POST | `/api/identity/platform/orgs` | PLATFORM:CONFIGURE | `PlatformController.onboard` |
| GET | `/api/identity/platform/orgs/{id}` | PLATFORM:VIEW | `PlatformController.orgDetails` |
| PUT | `/api/identity/platform/orgs/{id}` | PLATFORM:CONFIGURE | `PlatformController.updateOrg` |
| GET | `/api/identity/platform/orgs/{id}/features` | PLATFORM:VIEW | `PlatformController.features` |
| PUT | `/api/identity/platform/orgs/{id}/features` | PLATFORM:CONFIGURE | `PlatformController.setFeatures` |
| GET | `/api/identity/platform/plans` | PLATFORM:VIEW | `PlanController.list` |
| POST | `/api/identity/platform/plans` | PLATFORM:CONFIGURE | `PlanController.create` |
| DELETE | `/api/identity/platform/plans/{key}` | PLATFORM:CONFIGURE | `PlanController.delete` |
| PUT | `/api/identity/platform/plans/{key}` | PLATFORM:CONFIGURE | `PlanController.update` |
| GET | `/api/identity/platform/site/contact-requests` | PLATFORM:VIEW | `SiteAdminController.contactRequests` |
| PUT | `/api/identity/platform/site/contact-requests/{id}` | PLATFORM:CONFIGURE | `SiteAdminController.setStatus` |
| GET | `/api/identity/platform/site/features` | PLATFORM:VIEW | `SiteAdminController.features` |
| PUT | `/api/identity/platform/site/features/{key}` | PLATFORM:CONFIGURE | `SiteAdminController.updateFeature` |
| GET | `/api/identity/platform/summary` | PLATFORM:VIEW | `PlatformController.summary` |
| POST | `/api/identity/public/change-password` | open | `AuthController.changePassword` |
| POST | `/api/identity/public/contact` | open | `SiteController.contact` |
| POST | `/api/identity/public/demo-login` | open | `AuthController.demoLogin` |
| GET | `/api/identity/public/demo-users` | open | `AuthController.demoUsers` |
| POST | `/api/identity/public/login` | open | `AuthController.login` |
| GET | `/api/identity/public/options` | open | `AuthController.options` |
| POST | `/api/identity/public/otp/request` | open | `AuthController.requestOtp` |
| POST | `/api/identity/public/otp/verify` | open | `AuthController.verifyOtp` |
| GET | `/api/identity/public/site` | open | `SiteController.site` |
| POST | `/api/identity/refresh` | signed-in | `IdentityController.refresh` |
| GET | `/api/identity/roles` | signed-in | `IdentityController.roles` |
| POST | `/api/identity/roles` | ROLE:CONFIGURE | `IdentityController.createRole` |
| DELETE | `/api/identity/roles/{id}` | ROLE:CONFIGURE | `IdentityController.deleteRole` |
| PUT | `/api/identity/roles/{id}` | ROLE:CONFIGURE | `IdentityController.updateRole` |
| PUT | `/api/identity/roles/{id}/permissions` | ROLE:CONFIGURE | `IdentityController.setPermissions` |
| GET | `/api/identity/users` | USER:VIEW | `IdentityController.users` |
| POST | `/api/identity/users` | USER:CREATE | `IdentityController.createUser` |
| DELETE | `/api/identity/users/{id}` | USER:DELETE | `IdentityController.deleteUser` |
| PUT | `/api/identity/users/{id}` | USER:EDIT | `IdentityController.updateUser` |
| POST | `/api/identity/users/{id}/reset-password` | USER:EDIT | `IdentityController.resetPassword` |
