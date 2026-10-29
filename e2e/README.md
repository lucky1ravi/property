# PropertyOS end-to-end tests

A Playwright + TypeScript suite that covers the whole product through the real screens and the real API, role by role.
It is separate from the Java tests: it needs a running platform and drives it from the outside.

## What is covered

Every requirement is listed in [`requirements/requirements.json`](requirements/requirements.json) with an id such as `LEAS-03`.
Each test title starts with the id it proves (`[LEAS-03] enquiries create leads that staff can answer`), and
`npm run trace` rebuilds [`requirements/TRACEABILITY.md`](requirements/TRACEABILITY.md), which shows every requirement, its tests, and anything not covered.

| Folder | What it does |
|---|---|
| `tests/api` | Every service through the gateway: sign-in, roles and permissions, company isolation, platform admin, users and roles, configuration, portfolio, leasing, billing, maintenance, workflow, messages, notifications, AI, documents, operations, public site, reports. |
| `tests/ui` | Every screen in the browser: public site, sign-in and sign-out, menus for 13 people, every page per role, and the leasing, payments, maintenance, admin, platform, AI and files screens. |
| `tests/journeys` | Seven whole stories in the browser: a new company from scratch, leasing, rent, repair, owner, resident and vendor. |

## Run it on your machine

1. Build once: `cd backend && mvn -DskipTests package` and `cd frontend && npm ci && npm run build`.
2. Start PostgreSQL (the defaults are `postgres`/`postgres` on `localhost`; the stack uses a database called `e2e`, create it with `createdb e2e`).
3. Start the platform: `cd e2e && npm ci && npx playwright install chromium && npm run stack:start`.
4. Run: `npm test` (everything), `npm run test:api` or `npm run test:ui`.
5. Open the report with `npm run report`. Stop the platform with `npm run stack:stop`.

The default target is the local stack on `http://localhost:4173`.

## Run it against the live demo

```
E2E_MODE=live E2E_BASE_URL=https://dtqbmyulhq2jl.cloudfront.net E2E_API_URL=https://dtqbmyulhq2jl.cloudfront.net npm test
```

In live mode nothing is created: no companies, accounts or records. Tests that need to create data are skipped and the rest only read.
The demo users share one password (`E2E_DEMO_PASSWORD`, default is the one on the sign-in page).

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `E2E_BASE_URL` | `http://localhost:4173` | Where the web app is |
| `E2E_API_URL` | same as base | Where `/api` is |
| `E2E_MODE` | `local` | `local` may create data, `live` is read-only |
| `E2E_ALLOW_WRITES` | `true` | Set `false` to force read-only even locally |
| `E2E_DEMO_PASSWORD` | demo password | Password of the seeded demo users |

## GitHub Actions

`.github/workflows/e2e.yml` is manual (`workflow_dispatch`) only, so it never blocks a deploy. Choose `local` to build and start the platform on the runner, or `live` for the read-only run against a deployed site. The report and traceability file are uploaded as an artifact.

## How the tests stay stable

- Each test that needs data creates its own company, so tests do not depend on each other and can run in parallel.
- Other services learn about changes (a suspended company, a changed feature) within seconds, and listing publish and application screening are asynchronous, so the tests poll for the result instead of sleeping.
- Setup a person would not do themselves goes through the API; what the person does goes through the screens.

## Known limits

- The assistant proposing a change (and the confirm-once step) needs a language model. With none configured the assistant only answers, so that path is covered as far as the offline provider allows (it never proposes writes).
- Repair photo triage needs an image model; without one it returns a clear "AI offline" answer, which is what the test checks.
- Live mode is read-only by design.
- Chromium only.
