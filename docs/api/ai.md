# Ai service API

Base path `/api/ai` through the gateway (port 8080). Live, authoritative OpenAPI spec: `/api/ai/public/v3/api-docs`; browse it in Swagger UI at `/swagger-ui.html`.

Generated from the controllers in `backend/ai-service`: **12 endpoints**. *Permission* is the `FEATURE:ACTION` enforced by `PermissionInterceptor`; `signed-in` means any valid user (then limited by ownership checks and database row-level security); `open` means no token. `/internal/**` endpoints are service-to-service only and are not listed.

| Method | Path | Permission | Handler |
| --- | --- | --- | --- |
| POST | `/api/ai/actions/{id}/confirm` | AI_ASSISTANT:VIEW | `AiController.confirm` |
| GET | `/api/ai/audit` | AUDIT:VIEW | `AiController.audit` |
| GET | `/api/ai/broker/queue` | DASHBOARD:VIEW | `AiController.queue` |
| POST | `/api/ai/chat` | AI_ASSISTANT:VIEW | `AiController.chat` |
| GET | `/api/ai/opportunities` | OPPORTUNITY:VIEW | `AiController.opportunities` |
| GET | `/api/ai/status` | signed-in | `AiController.status` |
| GET | `/api/ai/studio/anomalies` | INVOICE:VIEW | `StudioController.anomalies` |
| POST | `/api/ai/studio/fair-housing` | LISTING:VIEW | `StudioController.fair` |
| POST | `/api/ai/studio/lease-abstract/{id}` | LEASE:VIEW | `StudioController.lease` |
| POST | `/api/ai/studio/photo-triage` | WORK_ORDER:VIEW | `StudioController.photo` |
| GET | `/api/ai/studio/resident-outlook` | LEASE:VIEW | `StudioController.outlook` |
| POST | `/api/ai/studio/statement-narrative/{id}` | OWNER_STATEMENT:VIEW | `StudioController.statement` |
