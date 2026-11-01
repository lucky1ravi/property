# Billing service API

Invoices, payments, deposits, expenses, ledger, owner statements, tax forms, and what companies pay PropertyOS.

| | |
| --- | --- |
| Base path | `/api/billing` through the gateway (port 8080) |
| Live contract | `/api/billing/public/v3/api-docs` (OpenAPI 3), browsable in Swagger UI at `/swagger-ui.html` |
| Endpoints | **33** (0 open to visitors, 0 for platform operators) |
| Source | `backend/billing-service` |

> This page is generated from the controllers by `node scripts/generate-api-docs.js`. Do not edit it by hand: change the controller and regenerate. CI fails when it is out of date.

**Reading the table.** *Access* is the `FEATURE:ACTION` permission enforced before the method runs (see [Security](../SECURITY.md)); `signed-in` means any valid user, further limited by ownership checks and row-level security; `open` means no token. `/internal/**` endpoints are service to service only and are not listed.

## Contents

- [aging](#aging) (1)
- [alerts](#alerts) (1)
- [collections](#collections) (2)
- [deadlines](#deadlines) (1)
- [deposits](#deposits) (2)
- [expenses](#expenses) (1)
- [invoices](#invoices) (4)
- [my](#my) (3)
- [payments](#payments) (3)
- [reports](#reports) (5)
- [run-cycle](#run-cycle) (1)
- [settings](#settings) (1)
- [statements](#statements) (4)
- [summary](#summary) (1)
- [tax](#tax) (3)

## aging

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/billing/aging` | `INVOICE:VIEW` | Returns outstanding balances grouped by how late they are. | `BillingController.aging` |

## alerts

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/billing/alerts/run` | `ALERT:CONFIGURE` | Sends due billing alerts now instead of waiting for the daily timer. | `BillingController.runAlerts` |

## collections

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/billing/collections` | `INVOICE:VIEW` | Lists residents with overdue balances. | `BillingController.collections` |
| POST | `/api/billing/collections/remind` | `INVOICE:EDIT` | Sends payment reminders to one resident, or to all overdue residents when none is given. | `BillingController.remind` |

## deadlines

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/billing/deadlines` | `ALERT:VIEW` | Lists upcoming billing deadlines such as deposit refunds. | `BillingController.deadlines` |

## deposits

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/billing/deposits` | `PAYMENT:VIEW` | Lists security deposits. | `BillingController.deposits` |
| POST | `/api/billing/deposits/{id}/settle` | `PAYMENT:APPROVE` | Settles a deposit, applying deductions and refunding the rest. | `BillingController.settle` |

## expenses

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/billing/expenses` | `OWNER_STATEMENT:CREATE` | Records a property expense that will appear on the owner's statement. | `BillingController.expense` |

## invoices

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/billing/invoices` | `INVOICE:VIEW` | Lists invoices, optionally by status, resident or kind of charge. | `BillingController.invoices` |
| POST | `/api/billing/invoices` | `INVOICE:CREATE` | Creates a one-off charge for a resident. | `BillingController.charge` |
| GET | `/api/billing/invoices/{id}` | `INVOICE:VIEW` | Returns one invoice with its payments and history. | `BillingController.invoice` |
| POST | `/api/billing/invoices/{id}/void` | `INVOICE:EDIT` | Voids an invoice. | `BillingController.voidInvoice` |

## my

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/billing/my` | `PAYMENT:VIEW` | Returns the signed-in resident's balance, invoices, payments and saved payment methods. | `BillingController.mine` |
| POST | `/api/billing/my/methods` | `PAYMENT:CREATE` | Saves a payment method (type and last four digits only) for the signed-in resident. | `BillingController.addMethod` |
| PUT | `/api/billing/my/methods/{id}/autopay` | `PAYMENT:CREATE` | Turns autopay on or off for one of the resident's payment methods. | `BillingController.autopay` |

## payments

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/billing/payments` | `PAYMENT:VIEW` | Lists payments, optionally for one resident. | `BillingController.payments` |
| POST | `/api/billing/payments` | `PAYMENT:CREATE` | Records a payment and applies it to open invoices. | `BillingController.pay` |
| GET | `/api/billing/payments/quote` | `PAYMENT:VIEW` | Quotes the processing fee and total for an amount and payment method before the resident pays. | `BillingController.quote` |

## reports

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/billing/reports/fees` | `REPORT:VIEW` | Late fees, returned-payment fees and other charges by month: charged, collected and cancelled. | `BillingReportController.fees` |
| GET | `/api/billing/reports/income` | `REPORT:VIEW` | Charged, collected, spent and kept, by property and month, for a period (default the last 6 months). | `BillingReportController.income` |
| GET | `/api/billing/reports/payments` | `REPORT:VIEW` | How payments are going, by month and method: how many, how much, how many failed. | `BillingReportController.payments` |
| GET | `/api/billing/reports/receivables` | `REPORT:VIEW` | One row per resident with a balance: how much is owed, how old it is (current, 1-30, 31-60, 61-90, over 90 days) and since when. | `BillingReportController.receivables` |
| GET | `/api/billing/reports/transactions` | `REPORT:VIEW` | Every payment, charge and expense behind a figure: filter with propertyId and month (yyyy-MM), newest first. | `BillingReportController.transactions` |

## run-cycle

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/billing/run-cycle` | `INVOICE:CREATE` | Runs the billing cycle for active leases: creates rent charges, applies late fees and processes autopay according to the company's invoicing settings. | `BillingController.runCycle` |

## settings

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/billing/settings` | `PAYMENT:VIEW` | Returns the company's payment settings: accepted methods, partial payments, autopay, convenience fees and payment limits. | `BillingController.settings` |

## statements

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/billing/statements` | `OWNER_STATEMENT:VIEW` | Lists owner statements. | `BillingController.statements` |
| POST | `/api/billing/statements` | `OWNER_STATEMENT:CREATE` | Generates an owner statement for a period. | `BillingController.generate` |
| GET | `/api/billing/statements/{id}` | `OWNER_STATEMENT:VIEW` | Returns one owner statement. | `BillingController.statement` |
| POST | `/api/billing/statements/{id}/publish` | `OWNER_STATEMENT:CREATE` | Publishes a draft statement to the owner. | `BillingController.publish` |

## summary

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/billing/summary` | `INVOICE:VIEW` | Returns billed, collected and outstanding totals for the dashboard. | `BillingController.summary` |

## tax

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/billing/tax/1099` | `TAX_REPORT:VIEW` | Returns owners' 1099 status and amounts for a year; defaults to the current year. | `BillingController.owner1099` |
| PUT | `/api/billing/tax/owners/{ownerId}/w9` | `TAX_REPORT:EDIT` | Records an owner's W-9 status and the last four digits of their tax id. | `BillingController.w9` |
| GET | `/api/billing/tax/summary` | `TAX_REPORT:VIEW` | Returns the year's income and expense totals for tax reporting; defaults to the current year. | `BillingController.taxSummary` |
