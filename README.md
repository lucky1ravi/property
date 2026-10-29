# PropertyOS

AI-assisted property management for US residential portfolios: leasing, rent and payments, maintenance, owner reporting, and a broker cockpit that turns a large portfolio into a short daily queue.

- **Backend:** Spring Boot 3.4 / Java 21 microservices (identity, leasing, billing, maintenance, workflow, ai) behind an API gateway. PostgreSQL with one schema per service, Flyway migrations, row-level security.
- **Frontend:** React 18 + TypeScript micro-frontends (Vite Module Federation): shell, leasing, payments, maintenance, broker, admin, insights. Tailwind, Framer Motion.
- **AI:** OpenAI or AWS Bedrock, with an offline rule-based fallback so the product works without a key. Guardrails: prompt-injection screening, PII redaction, fair-housing screening, rate limits, confirm-before-write, full audit.

## Run it on your machine (Windows, macOS, Linux)

Prerequisites: Docker, JDK 21, Maven 3.9+, Node 20+.

```powershell
# 1. Backend: Postgres + all services (Windows)
.\scripts\start-local.ps1          # stop with .\scripts\stop-local.ps1

# 2. Frontend
cd frontend
npm install
npm run build
npm run preview                    # http://localhost:4173
```

Gateway: http://localhost:8080. Services: 8081 identity, 8082 leasing, 8083 billing, 8084 maintenance, 8085 workflow, 8086 ai, 8087 documents. Logs are in `logs/`.
Open http://localhost:4173, pick a demo persona on the sign-in page. The public rental site is at `/homes`.

To use a real LLM, set environment variables before starting the AI service (never commit keys):

| Variable | Purpose |
| --- | --- |
| `AI_PROVIDER` | `openai`, `bedrock` or `none` (default when no key) |
| `OPENAI_API_KEY` | OpenAI key |
| `OPENAI_MODEL` | Optional model name |
| `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `BEDROCK_MODEL_ID` | Bedrock |

## Deploy

See [docs/DEPLOY.md](docs/DEPLOY.md): Railway (backend + Postgres) and Vercel (frontend), about 20 minutes.

## Repository layout

```
backend/    platform-common, api-gateway, six services, Dockerfile
frontend/   apps/{shell,leasing,payments,maintenance,broker,admin,insights}, packages/ui
infra/      docker-compose.yml (local Postgres)
scripts/    start-local.ps1, stop-local.ps1
docs/       deployment guide
```
