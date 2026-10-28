# Deploying PropertyOS

Two hosts, about 20 minutes: **Railway** runs the backend and PostgreSQL, **Vercel** serves the frontend.

You never put secrets in the repository. Set every value below in the host's dashboard.

## 1. Railway: database and backend

1. Create a Railway project. Add **PostgreSQL** (New, Database, PostgreSQL). Railway creates the variables `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD`, `PGDATABASE`.
2. Add a service from the GitHub repo `lucky1ravi/property`, branch `develop`. In the service settings set **Root Directory** to `backend`. Railway finds `backend/Dockerfile` and `backend/railway.json`.
3. Under **Variables**, add:

| Variable | Value |
| --- | --- |
| `PGHOST` | `${{Postgres.PGHOST}}` (reference the database service) |
| `PGPORT` | `${{Postgres.PGPORT}}` |
| `PGUSER` | `${{Postgres.PGUSER}}` |
| `PGPASSWORD` | `${{Postgres.PGPASSWORD}}` |
| `PGDATABASE` | `${{Postgres.PGDATABASE}}` |
| `JWT_SECRET` | a random string of 48+ characters |
| `INTERNAL_TOKEN` | another random string |
| `ALLOWED_ORIGINS` | your Vercel URL, e.g. `https://propertyos.vercel.app` (add it after step 2) |
| `SERVICE_HEAP` | `-Xmx220m` (optional) |
| `DEMO_MODE` | `true` for the interview demo; `false` in production |
| `AI_PROVIDER` | `openai` or `bedrock` (leave unset to use the offline rules) |
| `OPENAI_API_KEY` | your key (set it yourself in the dashboard) |

For Bedrock use `AI_PROVIDER=bedrock`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `BEDROCK_MODEL_ID`.

4. Under **Settings, Networking**, click **Generate Domain**. Note the URL, e.g. `https://propertyos-api.up.railway.app`.
5. Size: with no `SERVICE` variable, the container runs all six services plus the gateway. Give it **2 GB RAM**. First start takes 1 to 2 minutes (migrations and demo data).

Check: open `https://<your-api-domain>/actuator/health`. It returns `{"status":"UP"}`.

### Production topology (separate services)

The same image runs one service per container. Create six more Railway services from the same repo and root directory, and give each `SERVICE` set to its name (`identity-service`, `leasing-service`, `billing-service`, `maintenance-service`, `workflow-service`, `ai-service`, `api-gateway`). On each, set the shared variables above plus the internal URLs: `IDENTITY_URL`, `LEASING_URL`, `BILLING_URL`, `MAINTENANCE_URL`, `WORKFLOW_URL`, `AI_URL` (Railway private networking, e.g. `http://identity-service.railway.internal:8080`). Only the gateway gets a public domain. Start `identity-service` first on a new database; it creates the shared schema.

## 2. Vercel: frontend

1. Import the same GitHub repo. Set **Root Directory** to `frontend`, branch `develop`. Framework preset: Other. `frontend/vercel.json` supplies the build command (`npm run build`), output directory (`dist`) and routing.
2. Environment variable (Production and Preview): `VITE_API_URL` = the Railway gateway URL from step 1.4, with no trailing slash.
3. Deploy. Then copy the Vercel URL into Railway's `ALLOWED_ORIGINS` and redeploy the backend.

## 3. Verify

- `/homes` shows rentals from the database.
- Sign in with a demo persona (Broker, Org admin, Resident, Owner, Vendor, Platform admin).
- Admin, Configuration: **Reset demo data** restores the sample portfolio any time.
- Broker cockpit, assistant: ask "Who is overdue on rent?". With no LLM key the answer comes from built-in rules and says so; with a key the model answers and calls the same permission-checked tools.

## Cost (approximate, monthly)

| Item | Plan | Cost |
| --- | --- | --- |
| Railway backend (2 GB) + Postgres | Hobby / usage | $10 to 20 |
| Vercel frontend | Hobby | $0 |
| LLM (`gpt-4o-mini` class) | usage | under $5 for a demo |

## Security notes

- Rotate `JWT_SECRET` and `INTERNAL_TOKEN` per environment. Service-to-service calls require `INTERNAL_TOKEN`.
- Set `DEMO_MODE=false` for any real tenant data. It disables demo sign-in and the reset action.
- Postgres row-level security is enforced for the application role; the gateway rate-limits public and signed-in traffic.
