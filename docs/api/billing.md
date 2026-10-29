# Billing service API

Base path `/api/billing` through the gateway (port 8080). Live, authoritative OpenAPI spec: `/api/billing/public/v3/api-docs`; browse it in Swagger UI at `/swagger-ui.html`.

Generated from the controllers in `backend/billing-service`: **28 endpoints**. *Permission* is the `FEATURE:ACTION` enforced by `PermissionInterceptor`; `signed-in` means any valid user (then limited by ownership checks and database row-level security); `open` means no token. `/internal/**` endpoints are service-to-service only and are not listed.

| Method | Path | Permission | Handler |
| --- | --- | --- | --- |
| GET | `/api/billing/aging` | INVOICE:VIEW | `BillingController.aging` |
| POST | `/api/billing/alerts/run` | ALERT:CONFIGURE | `BillingController.runAlerts` |
| GET | `/api/billing/collections` | INVOICE:VIEW | `BillingController.collections` |
| POST | `/api/billing/collections/remind` | INVOICE:EDIT | `BillingController.remind` |
| GET | `/api/billing/deadlines` | ALERT:VIEW | `BillingController.deadlines` |
| GET | `/api/billing/deposits` | PAYMENT:VIEW | `BillingController.deposits` |
| POST | `/api/billing/deposits/{id}/settle` | PAYMENT:APPROVE | `BillingController.settle` |
| POST | `/api/billing/expenses` | OWNER_STATEMENT:CREATE | `BillingController.expense` |
| GET | `/api/billing/invoices` | INVOICE:VIEW | `BillingController.invoices` |
| POST | `/api/billing/invoices` | INVOICE:CREATE | `BillingController.charge` |
| GET | `/api/billing/invoices/{id}` | INVOICE:VIEW | `BillingController.invoice` |
| POST | `/api/billing/invoices/{id}/void` | INVOICE:EDIT | `BillingController.voidInvoice` |
| GET | `/api/billing/my` | PAYMENT:VIEW | `BillingController.mine` |
| POST | `/api/billing/my/methods` | PAYMENT:CREATE | `BillingController.addMethod` |
| PUT | `/api/billing/my/methods/{id}/autopay` | PAYMENT:CREATE | `BillingController.autopay` |
| GET | `/api/billing/payments` | PAYMENT:VIEW | `BillingController.payments` |
| POST | `/api/billing/payments` | PAYMENT:CREATE | `BillingController.pay` |
| GET | `/api/billing/payments/quote` | PAYMENT:VIEW | `BillingController.quote` |
| POST | `/api/billing/run-cycle` | INVOICE:CREATE | `BillingController.runCycle` |
| GET | `/api/billing/settings` | PAYMENT:VIEW | `BillingController.settings` |
| GET | `/api/billing/statements` | OWNER_STATEMENT:VIEW | `BillingController.statements` |
| POST | `/api/billing/statements` | OWNER_STATEMENT:CREATE | `BillingController.generate` |
| GET | `/api/billing/statements/{id}` | OWNER_STATEMENT:VIEW | `BillingController.statement` |
| POST | `/api/billing/statements/{id}/publish` | OWNER_STATEMENT:CREATE | `BillingController.publish` |
| GET | `/api/billing/summary` | INVOICE:VIEW | `BillingController.summary` |
| GET | `/api/billing/tax/1099` | TAX_REPORT:VIEW | `BillingController.owner1099` |
| PUT | `/api/billing/tax/owners/{ownerId}/w9` | TAX_REPORT:EDIT | `BillingController.w9` |
| GET | `/api/billing/tax/summary` | TAX_REPORT:VIEW | `BillingController.taxSummary` |
