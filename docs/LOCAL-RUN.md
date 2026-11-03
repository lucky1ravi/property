# Running everything on your own machine

Two parts. **Part 1 is a sequence: copy the blocks one after the other, in order**, and you go from nothing to a running system, then through every kind of test. **Part 2 is reference** (what each performance test means, settings, troubleshooting).

Commands are for Windows PowerShell. Where Git Bash is needed it says so.

## Part 1. The sequence

### Step 0. Check your tools

| Need | Version | Check |
| --- | --- | --- |
| JDK | 21 or newer (22 works) | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Node | 20+ | `node -v` |
| Docker Desktop | running | `docker ps` |

### Step 1. Set up this PowerShell window (do this first, in every new window)

Edit the first line to where you cloned the repository. Everything below uses `$REPO`, so each block can be pasted from any folder.

```powershell
$REPO = "D:\workspace\propertyos"
$env:JAVA_HOME = "C:\Program Files\Java\jdk-22"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC"
cd $REPO
```

The last two lines matter: if your machine's time zone is one PostgreSQL does not know (for example `Asia/Calcutta`), the services and tests fail with `invalid value for parameter "TimeZone"`. Forcing UTC avoids it. Open a new window and these settings are gone, so repeat this step there.

### Step 2. Unit tests (about a minute, no database)

```powershell
cd $REPO\backend
mvn -B test
```

One module or one class instead:

```powershell
mvn -B test -pl platform-common
mvn -B test -pl document-service -Dtest=FileRulesTest
```

### Step 3. Start the database (PostgreSQL, port 5433, 300 connections)

This is its own command. The compose file (`infra/docker-compose.yml`) starts PostgreSQL with `max_connections=300` (PostgreSQL's own default is 100), so seven services with a bigger connection pool each fit. The `propertyos-data` volume keeps your data across restarts.

```powershell
cd $REPO
docker compose -f infra/docker-compose.yml up -d
docker exec propertyos-db psql -U postgres -c "show max_connections"
```

The second command should print `300`. If it prints `100`, the container was created before this setting existed: run `docker compose -f infra/docker-compose.yml up -d --force-recreate` and check again (your data is kept). If Docker says the container name `propertyos-db` is already in use by something that was not created with compose, remove it with `docker rm -f propertyos-db` and run the first command again; the demo data is recreated when the services start.

`start-local.ps1` in the next step runs the same compose command, so Step 3 is the explicit, checkable version of something the script would also do.

### Step 4. Build and start the back end (eight services)

```powershell
cd $REPO
powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1
```

The script is run through `powershell -ExecutionPolicy Bypass -File` because PowerShell's default execution policy often refuses a plain `.\scripts\start-local.ps1`. It builds every service, makes sure the database is up, starts the eight services and waits until each answers. It sets `DB_POOL_SIZE=20` for each service (seven services times 20 is 140 connections, inside the 300) unless you set `DB_POOL_SIZE` yourself or in `.env.local`. This only affects your machine: on AWS the pool size is the `db_pool_size` Terraform variable.

Check it:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

Also open <http://localhost:8080/swagger-ui.html>. Logs are in `logs\`. To use a real AI model, set `$env:AI_PROVIDER = "openai"` and `$env:OPENAI_API_KEY = "..."` before this step; without them the assistant answers from its built-in rules.

Variations:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -SkipBuild                                   # start the jars already built
powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -Only leasing-service,billing-service        # just some services (identity always starts)
```

| Service | Port | | Service | Port |
| --- | --- | --- | --- | --- |
| api-gateway | 8080 | | maintenance-service | 8084 |
| identity-service | 8081 | | workflow-service | 8085 |
| leasing-service | 8082 | | ai-service | 8086 |
| billing-service | 8083 | | document-service | 8087 |

### Step 5. Build and serve the front end

```powershell
cd $REPO\frontend
npm install
npm run build
```

Then start the web server. **This command keeps running, so open a second PowerShell window for anything after it** (repeat Step 1 there):

```powershell
cd $REPO\frontend
npm run preview
```

Open <http://localhost:4173/signin> and pick a demo person. The public rental site is at `/homes`.

While developing the front end, rebuild on every change instead of `npm run build`:

```powershell
cd $REPO\frontend
node scripts/dev.mjs
npm test
```

### Step 6. Integration and system tests (about ten minutes)

These start eight JVMs and use a throw-away database, so stop your running services first (the jars are rebuilt, and Windows locks them).

```powershell
cd $REPO
powershell -ExecutionPolicy Bypass -File .\scripts\stop-local.ps1
docker rm -f pos-it
docker run -d --name pos-it -p 5439:5432 -e POSTGRES_PASSWORD=postgres postgres:16
```

Wait a few seconds for the database to start, then:

```powershell
cd $REPO\backend
$env:IT_JDBC_URL = "jdbc:postgresql://localhost:5439/postgres"
$env:IT_DB_USER = "postgres"; $env:IT_DB_PASSWORD = "postgres"
mvn -B verify
```

`mvn -B verify` runs everything: unit tests, each service's integration tests, then the system tests and the coverage gate (fails below 90% lines or 72% branches). The coverage report is `backend\platform-system-tests\target\system\coverage\index.html`. Shorter runs once the jars are built:

```powershell
mvn -B verify -rf :platform-system-tests
mvn -B verify -rf :platform-system-tests "-Dit.test=PlatformConsoleIT"
```

The first runs only the system tests; the second runs one system-test class. See [Testing](TESTING.md#3-backend) for what each layer is. Without Docker, point `IT_JDBC_URL` at any PostgreSQL 16 you have.

### Step 7. Performance tests (Gatling)

The platform must run with the demo users and with the gateway's rate limits raised, otherwise the gateway (correctly) throttles the test. Restart the stack that way (Step 3's database with 300 connections and Step 4's pool of 20 per service are what stop the database becoming the bottleneck):

```powershell
cd $REPO
$env:RATE_LIMIT_USER_PER_MINUTE = "1000000"; $env:RATE_LIMIT_PUBLIC_PER_MINUTE = "1000000"
$env:DEMO_MODE = "true"
powershell -ExecutionPolicy Bypass -File .\scripts\stop-local.ps1
docker compose -f infra/docker-compose.yml up -d
powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -SkipBuild
docker exec propertyos-db psql -U postgres -c "show max_connections"
```

Then run the simulations, smoke first. **Quote any `-D` option that has a dot in its name** (PowerShell splits it otherwise):

```powershell
cd $REPO\performance
mvn gatling:test
mvn gatling:test "-Dgatling.simulationClass=com.propertyos.perf.LoadSimulation" -Dusers=50 -DholdSeconds=300
mvn gatling:test "-Dgatling.simulationClass=com.propertyos.perf.StressSimulation" -Dusers=40
mvn gatling:test "-Dgatling.simulationClass=com.propertyos.perf.SpikeSimulation" -Dusers=40
mvn gatling:test "-Dgatling.simulationClass=com.propertyos.perf.SoakSimulation" -Dusers=30 -DholdSeconds=3600
```

Open the report: `performance\target\gatling\<simulation>-<timestamp>\index.html`. What each simulation means and how to read the report are in [Part 2](#performance-tests-what-each-one-means).

When you are done, put the gateway back to normal limits:

```powershell
cd $REPO
Remove-Item Env:RATE_LIMIT_USER_PER_MINUTE, Env:RATE_LIMIT_PUBLIC_PER_MINUTE
powershell -ExecutionPolicy Bypass -File .\scripts\stop-local.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\start-local.ps1 -SkipBuild
```

Never point these tests at production: the writes are small (work orders titled "Perf test") but real, and the load is deliberate.

### Step 8. Browser end-to-end tests (Playwright)

The suite brings its own isolated stack (its own database called `e2e`, the eight services, and a static web server on 4173), so it never touches your dev data. Stop your normal stack first because they share ports. Also press `Ctrl+C` in the window running `npm run preview` from Step 5: `stop-local.ps1` does not stop it, and while it runs it holds port 4173 and locks `frontend\dist`, so the build below fails with `EPERM`:

```powershell
cd $REPO
powershell -ExecutionPolicy Bypass -File .\scripts\stop-local.ps1
cd $REPO\e2e
npm install
npx playwright install chromium
```

Make the databases and build what it runs (the `pos-it` container from Step 6 is reused; recreate it as in Step 6 if you removed it):

```powershell
docker exec pos-it psql -U postgres -c "create database e2e"
cd $REPO\frontend; npm run build
cd $REPO\backend; mvn -B -DskipTests clean package
```

Start the stack. **This needs Git Bash** (open it in `$REPO\e2e`):

```bash
export JAVA_TOOL_OPTIONS=-Duser.timezone=UTC     # same reason as Step 1; Git Bash does not inherit it from PowerShell
export PGPORT=5439 PGDATABASE=e2e
export E2E_FAKE_AI=1          # optional: a stand-in AI model so AI answers are real
bash tools/local-stack.sh start
```

Run the tests from PowerShell:

```powershell
cd $REPO\e2e
npx playwright test
```

Useful variations:

```powershell
npx playwright test --project=api                       # API tests only
npx playwright test --project=chromium                  # browser tests only
npx playwright test tests/journeys/lifecycle.spec.ts    # one file (the long company-to-move-out journey)
npx playwright test -g "JRNY-09"                        # by test id
npx playwright test --headed --workers=1                # watch it run
npx playwright show-report                              # HTML report after a run
```

Stop the e2e stack when finished (Git Bash): `bash tools/local-stack.sh stop`.

Against the normal stack from Steps 3 to 5 instead (the gateway on 8080 plus `npm run preview` on 4173), skip the database and stack lines and point the tests at it. **Set both addresses**: `npm run preview` serves only the web files and does not forward `/api`, so the API tests need the gateway address as well (with only the first set, every API test fails):

```powershell
cd $REPO\e2e
$env:E2E_BASE_URL = "http://localhost:4173"
$env:E2E_API_URL = "http://localhost:8080"
npx playwright test tests/ui/auth.ui.spec.ts tests/api/auth.api.spec.ts
```

Tests that create companies or accounts run only in `E2E_MODE=local` (the default). Every journey the suite covers is drawn in [e2e/JOURNEYS.md](../e2e/JOURNEYS.md).

### Stop everything

```powershell
cd $REPO
powershell -ExecutionPolicy Bypass -File .\scripts\stop-local.ps1
docker compose -f infra/docker-compose.yml stop
```

---

## Part 2. Reference

### Doing it by hand instead of `start-local.ps1`

```powershell
cd $REPO
docker compose -f infra/docker-compose.yml up -d
cd $REPO\backend
mvn -B -DskipTests clean package
$env:DB_POOL_SIZE = "20"
# then one terminal per service, identity first and the gateway last, for example:
$env:PORT = 8081; java -jar identity-service/target/app.jar
```

### Performance tests: what each one means

Five simulations send the same mix of realistic users through the gateway. They differ in how much load they apply and for how long, so each answers a different question.

Four kinds of user, mixed 40, 30, 10 and 20 percent, with a think time of 1 to 3 seconds between steps so they behave like people rather than a flood.

| Journey | Share | What it does |
| --- | --- | --- |
| Property manager | 40% | Dashboard, portfolio, money, maintenance; one in ten also logs a work order |
| Resident (tenant) | 30% | What they owe, their lease and requests, notifications, documents |
| Prospect (public) | 20% | Browses public listings and opens one, with no sign-in |
| Accountant | 10% | Invoices, payments, collections, statements, tax summary |

| Command | The question it answers | Load shape | Passes when |
| --- | --- | --- | --- |
| `mvn gatling:test` (smoke) | Does the suite itself work against this environment? | One user per journey, about 20 seconds | No failed requests, p95 under 2 s |
| `LoadSimulation` | Does it cope with the expected peak? | Ramps to `users` in `rampSeconds` (30), then holds for `holdSeconds` | Errors at most 1% and p95 under 800 ms |
| `StressSimulation` | Where does it break? | Adds `users` more every step, six steps (40 gives up to 240 users) | Errors at most 10%; read the report to see where response time bends |
| `SpikeSimulation` | Does it survive a sudden surge and recover? | Normal load, a jump to 5x within seconds, then back | Errors at most 5% and p95 under 4x the normal target |
| `SoakSimulation` | Does it stay healthy over time? | Steady load for a long time (use `holdSeconds=3600` or more) | Same limits as load; look for response time or memory creeping up |

**Order:** run the smoke test first, always. A reasonable routine is smoke on every change, load before each release, and soak now and then.

#### 1. Smoke: `mvn gatling:test`
**What it is.** The default simulation (`SmokeSimulation`): one user of each journey (manager, resident, accountant, visitor) runs once, about 20 seconds in all.
**Why run it.** To prove the setup works before spending time on a long run: the platform is up, the demo users sign in, the rate limits are raised, and every endpoint in the journeys answers. It is not a load test.
**Passes when** no request fails and p95 is under 2 seconds. If it fails, fix that first; the bigger runs will fail for the same reason.

#### 2. Load: `LoadSimulation -Dusers=50 -DholdSeconds=300`
**What it is.** The expected busy period. It ramps up to 50 users over `rampSeconds` (30 s), then holds all 50 for 5 minutes, each behaving like a person with 1 to 3 seconds of think time between steps.
**Why run it.** To confirm the platform meets its service levels at the load you expect: run it before every release and after any change that could affect speed.
**Passes when** errors are at most 1% and p95 is under 800 ms.
**Look for** response times that rise during the hold (a sign of a bottleneck even at normal load).

#### 3. Stress: `StressSimulation -Dusers=40`
**What it is.** Load that keeps growing. It adds 40 more users at every step, six steps, so it climbs from 40 to 240 users.
**Why run it.** To find the limit: how many users the platform handles before it slows or starts failing, and what breaks first (database connections, threads, one slow service).
**Passes when** errors stay at most 10%. The real result is in the report: the point where response time bends upward while users keep rising is your capacity.
**Look for** requests per second that stop rising while users still rise, which means the platform is saturated.

#### 4. Spike: `SpikeSimulation -Dusers=40`
**What it is.** A sudden surge. Normal load (40 users), a jump to 5 times that within seconds, a short hold, then back to normal.
**Why run it.** To check the platform survives a burst without falling over and recovers by itself afterwards (a marketing email, everyone paying rent on the first of the month).
**Passes when** errors are at most 5% and p95 stays under 4 times the normal target.
**Look for** failures at the top of the spike and whether response times return to normal once it ends. A small connection pool shows up here first.

#### 5. Soak: `SoakSimulation -Dusers=30 -DholdSeconds=3600`
**What it is.** A moderate, steady load held for a long time (an hour here; use more to be thorough).
**Why run it.** To expose slow problems a short test cannot: memory leaks, connections that are never returned, growing queues, log or disk growth.
**Passes when** it meets the same limits as the load test for the whole hour.
**Look for** response time, memory or database connections that creep up over time instead of staying flat. Watch `/actuator/prometheus` while it runs.

Settings you can add as `-Dname=value` (none contains a dot, so they need no quotes):

| Setting | Default | Meaning |
| --- | --- | --- |
| `users` | 20 | Concurrent users. For stress, how many to add at each step |
| `rampSeconds` | 30 | Time to reach full load |
| `holdSeconds` | 60 | Time at full load (load and soak) |
| `p95Ms` | 800 | 95% of requests must be faster than this |
| `maxErrorPercent` | 1 | Failed requests allowed |
| `baseUrl` | `http://localhost:8080` | Gateway address |
| `org` | `Summit` | Demo company to sign in to |

#### Database connections

Every service keeps a pool of database connections. The shipped default is 4 per service, which a load or spike test can exhaust (the symptom is HTTP 500 with `Connection is not available, request timed out` in a service log). On your machine this is handled in two places, and AWS is not affected by either:

| Setting | Value | Where |
| --- | --- | --- |
| PostgreSQL `max_connections` | 300 | `infra/docker-compose.yml` (Step 3) |
| `DB_POOL_SIZE` per service | 20 unless you set it | `scripts/start-local.ps1` (Step 4) |

To use a different pool, set `$env:DB_POOL_SIZE = "30"` (or `DB_POOL_SIZE=30` in `.env.local`) before `start-local.ps1`, and keep seven times the pool under 300.

#### Reading the report

* **Response time percentiles:** p95 is the number to quote ("95% of requests finish within X ms"). The mean hides slow requests.
* **Active users over time against response time:** the point where response time starts climbing as users rise is your capacity limit.
* **Requests per second:** throughput. In a stress test, if it stops rising while users keep rising, the platform is saturated.
* **Errors:** a 5xx under load points to a bottleneck (database connections, thread pools). A 429 means the rate limiter engaged, so check the limits were raised.
* While a run is going, watch `/actuator/prometheus` on each service and the circuit-breaker state on `/actuator/health/downstream`.

On a shared development machine with the demo data the suite is a sanity check, not a capacity figure. For capacity planning run it against a production-like environment with production-like data. More detail and a baseline are in [performance/README.md](../performance/README.md).

### Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| A service behaves like the old code after you changed shared code | A plain `mvn package` can keep a stale `platform-common` jar inside a service's `app.jar`. Use `mvn -DskipTests clean package`, then restart |
| `release version 21 not supported` | Maven is using an older JDK. Set `JAVA_HOME` to JDK 21 or newer (Step 1) |
| `invalid value for parameter "TimeZone"` | Set `$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC"` (Step 1) |
| `Unknown lifecycle phase ".simulationClass=..."` | PowerShell split a `-D` option at its dot: quote it, `"-Dgatling.simulationClass=..."` |
| System tests test old behaviour | They launch the prebuilt jars: rebuild the changed service first |
| A page says "This part of PropertyOS couldn't load" | The front-end error boundary caught an exception: open the browser console, and check the matching API call's correlation id in `logs\` |
| `Connection is not available, request timed out` in a service log, or HTTP 500 under load | The service's database pool is exhausted. Check Step 3 printed 300 and that the stack was restarted with `start-local.ps1` (pool of 20) |
| A test fails with a port clash | A leftover service from an earlier run: `powershell -ExecutionPolicy Bypass -File .\scripts\stop-local.ps1`, or stop the Java processes, and rerun |
| `git pull` or `git push` fails with an SSL backend error on Windows | Use `git -c http.sslBackend=schannel ...` |
