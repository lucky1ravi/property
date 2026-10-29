# Leasing service API

Base path `/api/leasing` through the gateway (port 8080). Live, authoritative OpenAPI spec: `/api/leasing/public/v3/api-docs`; browse it in Swagger UI at `/swagger-ui.html`.

Generated from the controllers in `backend/leasing-service`: **47 endpoints**. *Permission* is the `FEATURE:ACTION` enforced by `PermissionInterceptor`; `signed-in` means any valid user (then limited by ownership checks and database row-level security); `open` means no token. `/internal/**` endpoints are service-to-service only and are not listed.

| Method | Path | Permission | Handler |
| --- | --- | --- | --- |
| POST | `/api/leasing/alerts/run` | ALERT:CONFIGURE | `AlertController.run` |
| GET | `/api/leasing/applications` | APPLICATION:VIEW | `LeasingController.applications` |
| GET | `/api/leasing/applications/{id}` | APPLICATION:VIEW | `LeasingController.application` |
| POST | `/api/leasing/applications/{id}/actions/{action}` | APPLICATION:VIEW | `LeasingController.applicationAction` |
| POST | `/api/leasing/applications/{id}/lease` | LEASE:CREATE | `LeasingController.leaseFromApplication` |
| POST | `/api/leasing/applications/{id}/screen` | APPLICATION:EDIT | `LeasingController.screen` |
| POST | `/api/leasing/applications/{id}/upload-link` | APPLICATION:EDIT | `LeasingController.uploadLink` |
| GET | `/api/leasing/deadlines` | ALERT:VIEW | `AlertController.deadlines` |
| GET | `/api/leasing/leads` | LISTING:VIEW | `LeasingController.leads` |
| POST | `/api/leasing/leads/{id}/respond` | LISTING:EDIT | `LeasingController.respond` |
| GET | `/api/leasing/leases` | LEASE:VIEW | `LeasingController.leases` |
| GET | `/api/leasing/leases/mine` | LEASE:VIEW | `LeasingController.myLease` |
| GET | `/api/leasing/leases/{id}` | LEASE:VIEW | `LeasingController.lease` |
| POST | `/api/leasing/leases/{id}/actions/{action}` | LEASE:VIEW | `LeasingController.leaseAction` |
| POST | `/api/leasing/leases/{id}/renewal` | LEASE:EDIT | `LeasingController.renewal` |
| GET | `/api/leasing/listings` | LISTING:VIEW | `LeasingController.listings` |
| POST | `/api/leasing/listings` | LISTING:CREATE | `LeasingController.createListing` |
| DELETE | `/api/leasing/listings/{id}` | LISTING:DELETE | `LeasingController.deleteListing` |
| GET | `/api/leasing/listings/{id}` | LISTING:VIEW | `LeasingController.listing` |
| PUT | `/api/leasing/listings/{id}` | LISTING:EDIT | `LeasingController.updateListing` |
| POST | `/api/leasing/listings/{id}/actions/{action}` | LISTING:VIEW | `LeasingController.listingAction` |
| POST | `/api/leasing/listings/{id}/ai-assist` | LISTING:EDIT | `LeasingController.aiAssist` |
| GET | `/api/leasing/owners` | OWNER:VIEW | `LeasingController.owners` |
| POST | `/api/leasing/owners` | OWNER:CREATE | `LeasingController.createOwner` |
| DELETE | `/api/leasing/owners/{id}` | OWNER:DELETE | `LeasingController.archiveOwner` |
| PUT | `/api/leasing/owners/{id}` | OWNER:EDIT | `LeasingController.updateOwner` |
| POST | `/api/leasing/owners/{id}/restore` | OWNER:DELETE | `LeasingController.restoreOwner` |
| GET | `/api/leasing/properties` | PROPERTY:VIEW | `LeasingController.properties` |
| POST | `/api/leasing/properties` | PROPERTY:CREATE | `LeasingController.createProperty` |
| DELETE | `/api/leasing/properties/{id}` | PROPERTY:DELETE | `LeasingController.archiveProperty` |
| GET | `/api/leasing/properties/{id}` | PROPERTY:VIEW | `LeasingController.property` |
| PUT | `/api/leasing/properties/{id}` | PROPERTY:EDIT | `LeasingController.updateProperty` |
| POST | `/api/leasing/properties/{id}/restore` | PROPERTY:DELETE | `LeasingController.restoreProperty` |
| POST | `/api/leasing/properties/{id}/units` | PROPERTY:CREATE | `LeasingController.createUnit` |
| GET | `/api/leasing/public/listings` | open | `PublicController.listings` |
| GET | `/api/leasing/public/listings/{id}` | open | `PublicController.listing` |
| POST | `/api/leasing/public/listings/{id}/applications` | open | `PublicController.apply` |
| POST | `/api/leasing/public/listings/{id}/enquiries` | open | `PublicController.enquire` |
| POST | `/api/leasing/public/listings/{id}/showings` | open | `PublicController.showing` |
| GET | `/api/leasing/residents` | USER:VIEW | `LeasingController.residents` |
| GET | `/api/leasing/showings` | SHOWING:VIEW | `LeasingController.showings` |
| POST | `/api/leasing/showings/{id}/actions/{action}` | SHOWING:EDIT | `LeasingController.showingAction` |
| GET | `/api/leasing/units` | PROPERTY:VIEW | `LeasingController.units` |
| DELETE | `/api/leasing/units/{id}` | PROPERTY:DELETE | `LeasingController.archiveUnit` |
| PUT | `/api/leasing/units/{id}` | PROPERTY:EDIT | `LeasingController.updateUnit` |
| PUT | `/api/leasing/units/{id}/market-rent` | PROPERTY:EDIT | `LeasingController.marketRent` |
| POST | `/api/leasing/units/{id}/restore` | PROPERTY:DELETE | `LeasingController.restoreUnit` |
