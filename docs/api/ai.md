# AI service API

The assistant, the AI studio tools, guardrails and the AI audit.

| | |
| --- | --- |
| Base path | `/api/ai` through the gateway (port 8080) |
| Live contract | `/api/ai/public/v3/api-docs` (OpenAPI 3), browsable in Swagger UI at `/swagger-ui.html` |
| Endpoints | **12** (0 open to visitors, 0 for platform operators) |
| Source | `backend/ai-service` |

> This page is generated from the controllers by `node scripts/generate-api-docs.js`. Do not edit it by hand: change the controller and regenerate. CI fails when it is out of date.

**Reading the table.** *Access* is the `FEATURE:ACTION` permission enforced before the method runs (see [Security](../SECURITY.md)); `signed-in` means any valid user, further limited by ownership checks and row-level security; `open` means no token. `/internal/**` endpoints are service to service only and are not listed.

## Contents

- [actions](#actions) (1)
- [audit](#audit) (1)
- [broker](#broker) (1)
- [chat](#chat) (1)
- [opportunities](#opportunities) (1)
- [status](#status) (1)
- [studio](#studio) (6)

## actions

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/ai/actions/{id}/confirm` | `AI_ASSISTANT:VIEW` | Approves or cancels a change the assistant proposed. | `AiController.confirm` |

## audit

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/ai/audit` | `AUDIT:VIEW` | Returns a summary of AI use: interactions, blocked requests, fallbacks, tokens and latency. | `AiController.audit` |

## broker

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/ai/broker/queue` | `DASHBOARD:VIEW` | Returns the broker's prioritised daily queue. | `AiController.queue` |

## chat

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| POST | `/api/ai/chat` | `AI_ASSISTANT:VIEW` | Sends a message to the assistant. | `AiController.chat` |

## opportunities

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/ai/opportunities` | `OPPORTUNITY:VIEW` | Lists revenue and savings opportunities found across the portfolio. | `AiController.opportunities` |

## status

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/ai/status` | signed-in | Returns the active provider, whether it is online, and the guardrails in force. | `AiController.status` |

## studio

| Method | Path | Access | What it does | Handler |
| --- | --- | --- | --- | --- |
| GET | `/api/ai/studio/anomalies` | `INVOICE:VIEW` | Scans invoices, statements and leases for unusual items. | `StudioController.anomalies` |
| POST | `/api/ai/studio/fair-housing` | `LISTING:VIEW` | Checks listing or message text for fair-housing risk. | `StudioController.fair` |
| POST | `/api/ai/studio/lease-abstract/{id}` | `LEASE:VIEW` | Summarises a lease's key terms. | `StudioController.lease` |
| POST | `/api/ai/studio/photo-triage` | `WORK_ORDER:VIEW` | Analyses a maintenance photo (JPG, PNG, WebP or GIF up to 8 MB) and suggests category, priority and next steps. | `StudioController.photo` |
| GET | `/api/ai/studio/resident-outlook` | `LEASE:VIEW` | Estimates renewal and risk outlook for active residents. | `StudioController.outlook` |
| POST | `/api/ai/studio/statement-narrative/{id}` | `OWNER_STATEMENT:VIEW` | Drafts a plain-language narrative for an owner statement. | `StudioController.statement` |
