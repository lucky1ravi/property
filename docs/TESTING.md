# Testing

| Layer | Tool | Command | Needs Docker |
| --- | --- | --- | --- |
| Backend unit | JUnit 5 (`*Test`) | `mvn test` | no |
| Backend integration | JUnit 5 + Testcontainers (`*IT`) | `mvn verify` | yes, or an external Postgres |
| Frontend unit | Vitest | `npm test` in `frontend` | no |

## What is covered

* **Unit:** JWT handling, user context, signed links, request parsing, error mapping, OpenAPI wording, AI guardrails, each AI studio tool (with a fake `ModelRunner`), document classification and photo-review decisions, API client error messages and formatters.
* **Integration** (real PostgreSQL, real Spring context, real HTTP): tenant isolation between companies, platform-admin governance, photo review end to end.

## Coverage: 90% minimum, measured across all test layers

`mvn verify` fails when combined **line coverage drops below 90%** (`coverage.minimum` in `backend/pom.xml`). The figure combines three layers, because most behaviour only shows up when the services run together:

| Layer | What it does |
| --- | --- |
| Unit tests (`*Test`) | Rules in isolation, with collaborators mocked |
| Per-service integration tests (`*IT` in each service) | One Spring context against a real PostgreSQL |
| System tests (`backend/platform-system-tests`) | Starts all 8 services as real processes behind the gateway, drives them over HTTP as every demo persona, and records coverage from each process with the JaCoCo agent |

`ZCoverageGateIT` runs last: it merges the three layers, prints a per-module table, writes an HTML report to `backend/platform-system-tests/target/system/coverage/index.html` and fails below the minimum. The system tests start from a fresh database and use a built-in fake language model, so they need no internet access and no API keys.

`OperationsIT` checks what operators rely on: health probes, metrics, correlation ids and security headers on every service.

Runtime: a full `mvn verify` takes about 10 minutes, mostly the system tests (eight JVMs). Run `mvn test` for the fast unit layer.

## How integration tests work

`platform-test-support` starts a `postgres:16-alpine` container once per JVM (`PostgresTestDatabase`), registers its URL as Spring properties, and provides `ApiClient`, a small HTTP client that returns 4xx bodies so tests can assert on error responses. Each test class seeds its own companies and users.

Without Docker, set `IT_JDBC_URL` (and `IT_DB_USER`, `IT_DB_PASSWORD`) to use an existing database instead.

## Writing tests

* Name unit tests `*Test` and integration tests `*IT`.
* Depend on constructors and small collaborators so a unit test needs no Spring context.
* Assert behaviour (status codes, results), not implementation.
* When a bug is found, add a failing test first.
