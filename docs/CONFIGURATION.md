# Configuration

Defaults are in each service's `application.yml` and `platform-common/src/main/resources/common.yml`. Environment variables override them. Never commit values.

## Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | per service | HTTP port |
| `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, `PGDATABASE` | local Postgres | Database |
| `JWT_SECRET` | dev value | Token signing key. Set 48+ random characters outside local development |
| `INTERNAL_TOKEN` | dev value | Service-to-service token for `/internal/**` |
| `ALLOWED_ORIGINS` | localhost | CORS origins |
| `DEMO_MODE` | `false` | Seeds sample data and shows the persona picker |
| `API_DOCS_ENABLED` | `true` | OpenAPI JSON and Swagger UI |
| `AI_PROVIDER` | none | `openai`, `bedrock` or none |
| `OPENAI_API_KEY`, `OPENAI_MODEL`, `OPENAI_REASONING_EFFORT` | | OpenAI |
| `AWS_REGION`, `AWS_BEARER_TOKEN_BEDROCK` or `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` | | Bedrock credentials |
| `BEDROCK_MODEL_ID`, `BEDROCK_VISION_MODEL_ID` | | Bedrock text and vision models |
| `IDENTITY_URL`, `LEASING_URL`, `BILLING_URL`, `MAINTENANCE_URL`, `WORKFLOW_URL`, `AI_URL`, `DOCUMENTS_URL` | localhost ports | Where the gateway and services find each other |
| `PUBLIC_API_URL` | | Public base URL used in emailed links |
| `SERVICE`, `SERVICE_HEAP` | | Container: run one service; heap per service in all-in-one mode |
| `VITE_API_URL` | | Frontend build: gateway URL |

## Per-company settings

Company administrators change these in Admin, Configuration (stored by identity-service, cached by `ConfigClient`): features, fees, invoicing, approvals, screening, SLA, renewals, AI, notifications, branding, state rules, payments, alerts, chat, documents, moderation, templates, custom fields. Defaults are in `platform-common/src/main/resources/config/defaults.json`.
