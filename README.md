# PropertyOS

AI-assisted property management for US residential portfolios: leasing, rent and payments, maintenance, documents and e-signature, owner reporting, and a broker cockpit that turns a large portfolio into a short daily queue.

[![CI](https://github.com/lucky1ravi/property/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/lucky1ravi/property/actions/workflows/ci.yml)

| Layer | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 3.4, Spring Cloud Gateway, seven microservices |
| Data | PostgreSQL 16, one schema per service, Flyway, row-level security (RLS) |
| Frontend | React 18, TypeScript, Vite Module Federation (shell + seven micro-frontends) |
| AI | OpenAI or AWS Bedrock, offline rule-based fallback, guardrails and audit |
| API docs | OpenAPI 3 via springdoc, one combined Swagger UI at the gateway |
| Tests | JUnit 5, Testcontainers (PostgreSQL), Vitest |

## Architecture at a glance

```
 Browser ── shell (React) ── micro-frontends: leasing, payments, maintenance,
    │                         documents, broker, admin, insights
    ▼
 api-gateway :8080  (JWT check, plan/feature gate, CORS, Swagger UI)
    ├── identity-service    :8081   users, companies, roles, plans, configuration
    ├── leasing-service     :8082   listings, applications, leases, showings
    ├── billing-service     :8083   charges, payments, owner statements, 1099
    ├── maintenance-service :8084   work orders, vendors, inspections
    ├── workflow-service    :8085   approvals, tasks, notifications
    ├── ai-service          :8086   assistant, AI studio tools, guardrails
    └── document-service    :8087   library, photos, e-signature, requirements
                │
                ▼
          PostgreSQL (schema per service + shared `platform` schema:
          event log, audit, RLS helpers)
```

Services talk to each other over REST (`/internal/**`, protected by a shared token) and through a transactional event log. Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Quick start

Prerequisites: Docker, JDK 21, Maven 3.9+, Node 20+.

```powershell
# Windows: Postgres + all services
.\scripts\start-local.ps1          # stop with .\scripts\stop-local.ps1

# Frontend
cd frontend
npm install
npm run build
npm run preview                    # http://localhost:4173
```

Open http://localhost:4173 and pick a demo persona on the sign-in page. The public rental site is at `/homes`.

| URL | What |
| --- | --- |
| http://localhost:8080/swagger-ui.html | Swagger UI for every service |
| http://localhost:8080/actuator/health | Gateway health |
| http://localhost:8080/api/&lt;service&gt;/public/v3/api-docs | Raw OpenAPI JSON per service |

## Tests

```bash
cd backend
mvn test        # unit tests only (fast, no Docker)
mvn verify      # unit + integration tests; integration tests start PostgreSQL with Testcontainers
cd ../frontend && npm test
```

No Docker? Point the integration tests at any PostgreSQL: `IT_JDBC_URL=jdbc:postgresql://localhost:5432/postgres IT_DB_USER=postgres IT_DB_PASSWORD=... mvn verify`. See [docs/TESTING.md](docs/TESTING.md).

## Configuration

Everything is an environment variable; no secrets live in the repository. The full table is in [docs/CONFIGURATION.md](docs/CONFIGURATION.md). The ones you will touch first:

| Variable | Purpose |
| --- | --- |
| `JWT_SECRET`, `INTERNAL_TOKEN` | Token signing key and service-to-service token (required outside local dev) |
| `PG*` | Database connection |
| `AI_PROVIDER` | `openai`, `bedrock` or unset (offline rules) |
| `API_DOCS_ENABLED` | `false` hides OpenAPI and Swagger UI (use in production) |
| `ALLOWED_ORIGINS`, `VITE_API_URL` | CORS and frontend-to-API wiring |

## Documentation

| Doc | Contents |
| --- | --- |
| [MICROSERVICES](docs/MICROSERVICES.md) | Resilience, observability, health, security and HA standards, and where each is implemented |
| [ARCHITECTURE](docs/ARCHITECTURE.md) | Services, data flow, design principles (SOLID) mapped to the code |
| [SECURITY](docs/SECURITY.md) | Authentication, permissions, tenant isolation with RLS, fail-closed rules |
| [AI](docs/AI.md) | Providers, tools, guardrails, audit |
| [API](docs/API.md) | Conventions, errors, pagination, how Swagger is generated |
| [CONFIGURATION](docs/CONFIGURATION.md) | Every environment variable and per-company setting |
| [PERFORMANCE](performance/README.md) | Gatling load, stress, spike and soak tests, with service levels and a baseline |
| [CI-CD](docs/CI-CD.md) | Push to develop and only the changed services and apps are tested, built and deployed |
| [AWS-DEPLOY](docs/AWS-DEPLOY.md) | ECS Fargate deployment in Mumbai: Terraform, GitHub Actions pipeline, cost, rollback |
| [TESTING](docs/TESTING.md) | Test strategy and how to run each layer |
| [DEPLOY](docs/DEPLOY.md) | Railway and Vercel |
| [CONTRIBUTING](CONTRIBUTING.md) | Branching, code style, checks that must pass |

## Repository layout

```
backend/
  platform-common/        shared kernel: security, RLS, errors, events, OpenAPI config
  platform-test-support/  Testcontainers database + HTTP client for integration tests
  api-gateway/            routing, auth, feature gate, Swagger UI
  *-service/              the seven domain services
frontend/
  apps/*                  micro-frontends
  packages/ui             shared components, API client, formatters
infra/                    docker-compose for local Postgres
docs/                     documentation
.github/                  CI workflow, PR template
```
