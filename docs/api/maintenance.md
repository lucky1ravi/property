# Maintenance service API

Work orders, vendors, vendor ratings, preventive maintenance and repair reports.

| | |
| --- | --- |
| Base path | `/api/maintenance` through the gateway (port 8080) |
| Live contract | `/api/maintenance/public/v3/api-docs` (OpenAPI 3), browsable in Swagger UI at `/swagger-ui.html` |
| Endpoints | **20** (0 open to visitors, 0 for platform operators) |
| Source | `backend/maintenance-service` |

> This page is generated from the controllers by `node scripts/generate-api-docs.js`. Do not edit it by hand: change the controller and regenerate. CI fails when it is out of date.

**Reading the table.** *Access* is the `FEATURE:ACTION` permission enforced before the method runs (see [Security](../SECURITY.md)); `signed-in` means any valid user, further limited by ownership checks and row-level security; `open` means no token. `/internal/**` endpoints are service to service only and are not listed.

## Contents

- [alerts](#alerts) (1)
- [deadlines](#deadlines) (1)
- [recurring](#recurring) (3)
- [reports](#reports) (3)
- [sla](#sla) (1)
- [stats](#stats) (1)
- [vendors](#vendors) (5)
- [work-orders](#work-orders) (5)

## alerts

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/maintenance/alerts/run` | `ALERT:CONFIGURE` | Sends due vendor alerts now instead of waiting for the daily timer. | `AlertController.run` |

## deadlines

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/maintenance/deadlines` | `ALERT:VIEW` | Lists vendor insurance expiries inside the alert horizon. | `AlertController.deadlines` |

## recurring

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/maintenance/recurring` | `WORK_ORDER:VIEW` | Lists recurring maintenance tasks. | `MaintenanceController.recurring` |
| POST | `/api/maintenance/recurring` | `WORK_ORDER:EDIT` | Adds a recurring maintenance task for a property. | `MaintenanceController.addRecurring` |
| POST | `/api/maintenance/recurring/run` | `WORK_ORDER:EDIT` | Creates work orders for recurring tasks that are due. | `MaintenanceController.runRecurring` |

## reports

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/maintenance/reports/costs` | `REPORT:VIEW` | Jobs, cost and speed grouped by by: property (default), category, vendor or priority. | `MaintenanceReportController.costs` |
| GET | `/api/maintenance/reports/jobs` | `REPORT:VIEW` | The jobs behind a figure. | `MaintenanceReportController.jobs` |
| GET | `/api/maintenance/reports/vendors` | `REPORT:VIEW` | A scorecard per vendor for the period: jobs, cost against estimate, speed, on-time share, and what people rate them. | `MaintenanceReportController.vendors` |

## sla

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/maintenance/sla/check` | `WORK_ORDER:EDIT` | Flags work orders past their SLA and escalates them. | `MaintenanceController.sla` |

## stats

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/maintenance/stats` | `WORK_ORDER:VIEW` | Returns open, emergency and SLA figures for the dashboard. | `MaintenanceController.stats` |

## vendors

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/maintenance/vendors` | `VENDOR:VIEW` | Lists vendors with workload and recent ratings. | `MaintenanceController.vendors` |
| POST | `/api/maintenance/vendors` | `VENDOR:CREATE` | Adds a vendor. | `MaintenanceController.createVendor` |
| PUT | `/api/maintenance/vendors/{id}` | `VENDOR:EDIT` | Changes a vendor's details or switches them off. | `MaintenanceController.updateVendor` |
| GET | `/api/maintenance/vendors/{id}/reviews` | `WORK_ORDER:VIEW` | The ratings this person may see, with the average and how the stars are spread. | `VendorReviewController.list` |
| POST | `/api/maintenance/vendors/{id}/reviews` | `WORK_ORDER:VIEW` | Rates a vendor, or changes the rating. | `VendorReviewController.rate` |

## work-orders

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/maintenance/work-orders` | `WORK_ORDER:VIEW` | Lists work orders, optionally by status or priority. | `MaintenanceController.list` |
| POST | `/api/maintenance/work-orders` | `WORK_ORDER:CREATE` | Creates a work order. | `MaintenanceController.create` |
| GET | `/api/maintenance/work-orders/{id}` | `WORK_ORDER:VIEW` | Returns one work order with its comments, history and allowed actions. | `MaintenanceController.get` |
| POST | `/api/maintenance/work-orders/{id}/actions/{action}` | `WORK_ORDER:VIEW` | Moves a work order through its workflow, for example assign, schedule, complete or close. | `MaintenanceController.act` |
| POST | `/api/maintenance/work-orders/{id}/comments` | `WORK_ORDER:VIEW` | Adds a comment, optionally internal (hidden from residents). | `MaintenanceController.comment` |
