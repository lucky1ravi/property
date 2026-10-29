# PropertyOS performance tests

Load, stress, spike and soak tests for the PropertyOS APIs, built with [Gatling](https://gatling.io) (Java). They send realistic traffic through the **API gateway**, as real clients do, and fail the run if response time or error rate goes beyond the agreed service levels.

Why Gatling: the scenarios are plain Java next to the code, run with Maven (no separate install), are easy to review in a pull request, and produce an HTML report with a chart for every API.

## What it tests

Four kinds of user, mixed 40 / 30 / 10 / 20 percent:

| Journey | What it does | Calls |
| --- | --- | --- |
| Property manager | Dashboard, portfolio, money, maintenance; one in ten also logs a work order | 11 reads + 1 write |
| Tenant | What they owe, their lease and requests, notifications, documents | 6 reads |
| Accountant | Invoices, payments, collections, statements, tax summary | 6 reads |
| Prospect (public) | Browses public listings, opens one; no sign-in | 2 reads |

Think time of 1 to 3 seconds sits between steps, so users behave like people rather than a flood. Each API is its own named line in the report.

| Simulation | Purpose | Passes when |
| --- | --- | --- |
| `SmokeSimulation` | One user per journey. Run it first, always (about 20 seconds) | no failed requests, p95 under 2 s |
| `LoadSimulation` | Expected peak: `users` at once for `holdSeconds` | errors at most `maxErrorPercent` (1%) and p95 under `p95Ms` (800 ms) |
| `StressSimulation` | Adds `users` more every step, six steps, to find the limit | errors at most 10%. Read the report to find where it bends |
| `SpikeSimulation` | Normal load, a jump to 5x within seconds, back again | errors at most 5%, p95 under 4x the target |
| `SoakSimulation` | Steady load for a long time (use `holdSeconds=3600` or more) to expose leaks | same as load |

## Running it

Prerequisites: JDK 21 and Maven. The platform must be running with **`DEMO_MODE=true`** (the tests sign in as the demo personas).

**1. Start the platform with the per-user rate limit raised.** The gateway limits each user to 600 requests a minute and each address to 120 public requests a minute, which is correct in production but would block a load test that reuses a few demo accounts:

```
RATE_LIMIT_USER_PER_MINUTE=1000000 RATE_LIMIT_PUBLIC_PER_MINUTE=1000000
```

**2. Run a simulation** from this folder:

```
mvn gatling:test                                                       # smoke test
mvn gatling:test -Dgatling.simulationClass=com.propertyos.perf.LoadSimulation -Dusers=50 -DholdSeconds=300
mvn gatling:test -Dgatling.simulationClass=com.propertyos.perf.StressSimulation -Dusers=40
mvn gatling:test -Dgatling.simulationClass=com.propertyos.perf.SpikeSimulation -Dusers=40
mvn gatling:test -Dgatling.simulationClass=com.propertyos.perf.SoakSimulation -Dusers=30 -DholdSeconds=3600
```

**3. Open the report:** `target/gatling/<simulation>-<timestamp>/index.html`.

| Setting | Default | Meaning |
| --- | --- | --- |
| `baseUrl` | `http://localhost:8080` | Gateway address |
| `org` | `Summit` | Demo company to sign in to |
| `users` | 20 | Concurrent users |
| `rampSeconds` | 30 | Time to reach full load |
| `holdSeconds` | 60 | Time at full load |
| `p95Ms` | 800 | 95% of requests must be faster than this |
| `maxErrorPercent` | 1 | Failed requests allowed |

## Reading the report

* **Response time percentiles:** p95 is the number to quote ("95% of requests finish within X ms"). The mean hides slow requests.
* **Active users over time vs response time:** the point where response time starts climbing as users rise is your capacity limit.
* **Requests per second:** throughput. In a stress test, if it stops rising while users keep rising, the platform is saturated.
* **Errors:** a 5xx under load points to a bottleneck (database connections, thread pools). A 429 means the rate limiter engaged.
* While a test runs, watch `/actuator/prometheus` on each service, and the circuit-breaker state on `/actuator/health/downstream`.

## Baseline

Measured on a single shared development machine (all eight services in small JVMs, one PostgreSQL, the **demo dataset of a few hundred rows**), so treat it as a sanity check of the suite rather than a capacity figure:

| Run | Users | Requests | Failed | p95 | p99 |
| --- | --- | --- | --- | --- | --- |
| Load | 20 for 60 s | 1,399 | 0 | 33 ms | 67 ms |
| Stress | 40 rising to 240 | 22,314 | 0 | 17 ms | 30 ms |
| Spike | 40 jumping to 200 | 6,662 | 0 | 12 ms | 34 ms |

For real capacity planning run the same suite against a production-like environment with production-like data volumes, and record the result here.

## Good practice

* Never run it against production. The writes are small (work orders titled "Perf test") but real, and the load is deliberate.
* Compare like with like: same data size, same instance sizes, same settings; change one thing at a time.
* Run the smoke test on every change, the load test before each release, and the soak test now and then.
* Raise the rate limits only for the test environment.
