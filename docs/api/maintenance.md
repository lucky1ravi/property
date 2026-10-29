# Maintenance service API

Base path `/api/maintenance` through the gateway (port 8080). Live, authoritative OpenAPI spec: `/api/maintenance/public/v3/api-docs`; browse it in Swagger UI at `/swagger-ui.html`.

Generated from the controllers in `backend/maintenance-service`: **15 endpoints**. *Permission* is the `FEATURE:ACTION` enforced by `PermissionInterceptor`; `signed-in` means any valid user (then limited by ownership checks and database row-level security); `open` means no token. `/internal/**` endpoints are service-to-service only and are not listed.

| Method | Path | Permission | Handler |
| --- | --- | --- | --- |
| POST | `/api/maintenance/alerts/run` | ALERT:CONFIGURE | `AlertController.run` |
| GET | `/api/maintenance/deadlines` | ALERT:VIEW | `AlertController.deadlines` |
| GET | `/api/maintenance/recurring` | WORK_ORDER:VIEW | `MaintenanceController.recurring` |
| POST | `/api/maintenance/recurring` | WORK_ORDER:EDIT | `MaintenanceController.addRecurring` |
| POST | `/api/maintenance/recurring/run` | WORK_ORDER:EDIT | `MaintenanceController.runRecurring` |
| POST | `/api/maintenance/sla/check` | WORK_ORDER:EDIT | `MaintenanceController.sla` |
| GET | `/api/maintenance/stats` | WORK_ORDER:VIEW | `MaintenanceController.stats` |
| GET | `/api/maintenance/vendors` | VENDOR:VIEW | `MaintenanceController.vendors` |
| POST | `/api/maintenance/vendors` | VENDOR:CREATE | `MaintenanceController.createVendor` |
| PUT | `/api/maintenance/vendors/{id}` | VENDOR:EDIT | `MaintenanceController.updateVendor` |
| GET | `/api/maintenance/work-orders` | WORK_ORDER:VIEW | `MaintenanceController.list` |
| POST | `/api/maintenance/work-orders` | WORK_ORDER:CREATE | `MaintenanceController.create` |
| GET | `/api/maintenance/work-orders/{id}` | WORK_ORDER:VIEW | `MaintenanceController.get` |
| POST | `/api/maintenance/work-orders/{id}/actions/{action}` | WORK_ORDER:VIEW | `MaintenanceController.act` |
| POST | `/api/maintenance/work-orders/{id}/comments` | WORK_ORDER:VIEW | `MaintenanceController.comment` |
