# Running everything on your own machine

Commands for Windows (PowerShell or Git Bash). Run them from the repository root unless a folder is named.

## Before you start

| Need | Version | Check |
| --- | --- | --- |
| JDK | 21 or newer (22 works) | `java -version` |
| Maven | 3.9+ | `mvn -v` |
| Node | 20+ | `node -v` |
| Docker Desktop | running | `docker ps` |

If `JAVA_HOME` points at an older JDK, set it for the session:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-22"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

If the database refuses to start a test with `invalid value for parameter "TimeZone"`, force the JVM to UTC:

```powershell
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=UTC"
```

## 1. Unit tests (JUnit)

Fast, no database, about a minute.

```powershell
cd backend
mvn -B test                                  # every module
mvn -B test -pl platform-common              # one module
mvn -B test -pl document-service -Dtest=FileRulesTest   # one class
```

## 2. Build and run the back end

One command builds all seven services and the gateway, starts PostgreSQL in Docker (port 5433) and waits until every port answers:

```powershell
.\scripts\start-local.ps1                    # build and start
.\scripts\start-local.ps1 -SkipBuild         # start the jars already built
.\scripts\start-local.ps1 -Only leasing-service,billing-service   # just some services (identity always starts)
.\scripts\stop-local.ps1                     # stop everything
```

Or by hand:

```powershell
docker run -d --name propertyos-db -p 5433:5432 -e POSTGRES_PASSWORD=postgres postgres:16
cd backend
mvn -B -DskipTests package                   # builds <service>/target/app.jar
# then, one terminal per service (identity first, gateway last), for example:
$env:PORT = 8081; java -jar identity-service/target/app.jar
```

| Service | Port | | Service | Port |
| --- | --- | --- | --- | --- |
| api-gateway | 8080 | | maintenance-service | 8084 |
| identity-service | 8081 | | workflow-service | 8085 |
| leasing-service | 8082 | | ai-service | 8086 |
| billing-service | 8083 | | document-service | 8087 |

Check it: http://localhost:8080/actuator/health and http://localhost:8080/swagger-ui.html. Logs are in `logs\`.

To use a real AI model, set `AI_PROVIDER=openai` and `OPENAI_API_KEY=...` before starting. Without them the assistant answers from its built-in rules.

## 3. Build and run the front end

```powershell
cd frontend
npm install
npm run build          # shell plus the seven micro-front-ends into frontend\dist
npm run preview        # serves the build on http://localhost:4173 and proxies /api to :8080
```

While developing, rebuild on every change instead:

```powershell
cd frontend
node scripts/dev.mjs   # watches and rebuilds; refresh the browser
npm test               # Vitest unit tests
```

Sign in on http://localhost:4173/signin and pick a demo person. The public rental site is at `/homes`.

## 4. Integration tests (JUnit + a real database)

These start eight JVMs and a throw-away database. They are the slow ones: about ten minutes.

```powershell
# a database for the tests (any empty PostgreSQL works)
docker run -d --name pos-it -p 5439:5432 -e POSTGRES_PASSWORD=postgres postgres:16

cd backend
$env:IT_JDBC_URL = "jdbc:postgresql://localhost:5439/postgres"
$env:IT_DB_USER = "postgres"; $env:IT_DB_PASSWORD = "postgres"
mvn -B verify                                              # unit + integration + the coverage gate
mvn -B verify -rf :platform-system-tests                   # only the system tests
mvn -B verify -rf :platform-system-tests -Dit.test=PlatformConsoleIT   # one integration test class
```

Stop any locally running services first (the jars are rebuilt and Windows locks them). `mvn verify` fails when line coverage is under 90% or branch coverage under 72%. The report is in `backend\platform-system-tests\target\system\coverage\index.html`.

Without Docker, point `IT_JDBC_URL` at any PostgreSQL 16 you have.

## 5. Performance tests (Gatling)

The platform must be running with the rate limits raised, otherwise the gateway (correctly) throttles a load test:

```powershell
$env:RATE_LIMIT_USER_PER_MINUTE = 1000000; $env:RATE_LIMIT_PUBLIC_PER_MINUTE = 1000000
$env:DEMO_MODE = "true"
.\scripts\start-local.ps1
```

Then from `performance\`:

```powershell
cd performance
mvn gatling:test                                                                                   # smoke, 20 seconds. Always first
mvn gatling:test -Dgatling.simulationClass=com.propertyos.perf.LoadSimulation  -Dusers=50 -DholdSeconds=300
mvn gatling:test -Dgatling.simulationClass=com.propertyos.perf.StressSimulation -Dusers=40
mvn gatling:test -Dgatling.simulationClass=com.propertyos.perf.SpikeSimulation  -Dusers=40
mvn gatling:test -Dgatling.simulationClass=com.propertyos.perf.SoakSimulation   -Dusers=30 -DholdSeconds=3600
```

Open `performance\target\gatling\<run>\index.html`. More in [performance/README.md](../performance/README.md).

## 6. End-to-end tests (Playwright)

The e2e suite brings its own isolated stack (its own database named `e2e`, the services, and a static web server on 4173), so it never touches your dev data.

```powershell
cd e2e
npm install
npx playwright install chromium       # first time only

# a database for it, and the stack
docker run -d --name pos-it -p 5439:5432 -e POSTGRES_PASSWORD=postgres postgres:16
docker exec pos-it psql -U postgres -c "create database e2e"
cd ..\frontend; npm run build; cd ..\backend; mvn -B -DskipTests package; cd ..\e2e
$env:PGPORT = "5439"
$env:E2E_FAKE_AI = "1"                 # optional: connect the AI service to a stand-in model so AI answers are real
bash tools/local-stack.sh start        # in Git Bash. Stop later with: bash tools/local-stack.sh stop

# run
npx playwright test                                     # everything
npx playwright test --project=api                       # API tests only
npx playwright test --project=chromium                  # browser tests only
npx playwright test tests/journeys/lifecycle.spec.ts    # one file (the long company-to-move-out journey)
npx playwright test -g "JRNY-09"                        # by test id
npx playwright test --headed --workers=1                # watch it run
npx playwright show-report                              # HTML report after a run
```

Against a stack you already started with `start-local.ps1` (port 8080 plus `npm run preview` on 4173), skip the Docker and stack lines and set `E2E_BASE_URL=http://localhost:4173`. Tests that create companies or accounts only run in `E2E_MODE=local` (the default).

Every journey and workflow the suite covers is drawn in [e2e/JOURNEYS.md](../e2e/JOURNEYS.md).

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| A service behaves like the old code after you changed shared code | A plain `mvn package` can keep a stale `platform-common` jar inside a service's `app.jar`. Use `mvn -DskipTests clean package`, then restart |
| `release version 21 not supported` | Maven is using an older JDK. Set `JAVA_HOME` to JDK 21 or newer (see the top of this page) |
| `invalid value for parameter "TimeZone"` in a test | Set `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` |
| System tests test old behaviour | They launch the prebuilt jars: rebuild the changed service first |
| A page says "This part of PropertyOS couldn't load" | The front-end error boundary caught an exception: open the browser console, and check the matching API call's correlation id in `logs\` |
| A test fails with a port clash | A leftover service from an earlier run: `.\scripts\stop-local.ps1`, or stop the Java processes, and rerun |
| `git pull` or `git push` fails with an SSL backend error on Windows | Use `git -c http.sslBackend=schannel ...` |
