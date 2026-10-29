# Continuous integration and delivery

Push to `develop` and GitHub Actions deploys **only what changed**. A change to one service rebuilds and restarts that
service alone, a change to one frontend app uploads that app alone, and nothing else is touched.

## What happens on a pull request (`ci.yml`)

1. **What changed** compares the branch with its base (`.github/scripts/changes.sh`).
2. If anything under `backend/` changed, the full backend runs: `mvn verify` (unit, integration and system tests, plus the 90% coverage gate).
3. If anything under `frontend/` changed, the frontend unit tests run and **only the apps that changed** are built.
4. **CI result** is one status check that is green when every job that had work passed. Require it in branch protection.

## What happens on a push to `develop` (`deploy.yml`)

```
changes ──► build (one job per changed service, in parallel)
              │   mvn -pl <service> -am verify      tests only that service and the shared code it uses
              │   docker build  Dockerfile.service  packages the tested jar (seconds, nothing is recompiled)
              ▼   docker push   <repo>:<service>-<commit>
        deploy-identity ─► deploy-workers (changed ones, in parallel) ─► deploy-gateway
                                                                              │
        frontend (only the changed apps; their files only are invalidated) ◄──┘
              ▼
        smoke check through CloudFront
```

* **Order.** Identity goes first because it prepares the shared database schema, the other services next, the gateway last.
* **Safety.** Each rollout waits until the new tasks are healthy. ECS's circuit breaker rolls back on its own, and the
  workflow fails so nothing after it runs. Tests run before an image is pushed, so a failing test never reaches AWS.
* **What counts as changed.**

  | You changed | Redeployed |
  | --- | --- |
  | `backend/leasing-service/**` | leasing-service only |
  | `backend/platform-common/**`, `backend/pom.xml`, `Dockerfile.service`, `docker-entrypoint.sh` | every backend service |
  | `frontend/apps/admin/**` | the admin app only |
  | `frontend/packages/**`, `frontend/package*.json` | every frontend app |
  | `infra/**`, `docs/**`, `performance/**` | nothing (Terraform is applied by hand, see below) |

* **"Changed" means changed since the last successful deploy.** The workflow keeps a git tag `deployed/<unit>` on the
  commit each service and app was last deployed from (see them under *Tags* on GitHub). If two pushes queue up, or a
  deploy fails, the next run still picks up everything that has not been deployed yet.
* **Run everything.** *Actions → Deploy to AWS → Run workflow → Rebuild and deploy every service and app.*

## Why it is faster

| Before | Now |
| --- | --- |
| Every deploy compiled all 8 services inside Docker with a cold Maven cache | Maven runs on the runner with a warm cache (`setup-java` caches `~/.m2`) |
| One image for everything, so every service restarted | One small image per service, only changed services restart |
| Tests, build and deploy by hand on a laptop | Tests, image build and rollout run on GitHub, in parallel per service |
| Frontend: all 8 apps rebuilt and uploaded, full CloudFront flush | Only changed apps built and uploaded, only their paths invalidated |

Building one service (tests included) takes about 20 seconds to a few minutes depending on its integration tests. The
shared-code case (`platform-common`) still builds every service, but in parallel jobs.

## One-time setup

1. `terraform apply` (creates the deploy role, and the per-service image retention policy).
2. Run the commands from `terraform output github_variables` in the repository (they set `AWS_ROLE_ARN`, `AWS_REGION`,
   `ECS_CLUSTER`, `ECR_REPOSITORY`, `FRONTEND_BUCKET`, `CLOUDFRONT_DISTRIBUTION_ID` and `APP_URL`). They go under
   *Settings → Secrets and variables → Actions → Variables*. There are no secrets: AWS access uses OIDC.
3. *Settings → Actions → General → Workflow permissions* must allow the workflow to push tags (the workflow asks for
   `contents: write`; keep the default token enabled).
4. *Settings → Branches*: protect `develop` and `main`, and require the **CI result** check.
5. Optional: add a **required reviewer** on a GitHub *Environment* if you want a person to approve each deploy.

The first run has no `deployed/*` tags, so it builds and deploys everything. After that it deploys only changes.

## Database and infrastructure changes

* **Database migrations** run when the owning service starts (Flyway), so they ship with that service.
* **Infrastructure (`infra/aws`)** is not applied by the pipeline. Run `terraform apply` yourself. Services keep running
  the images the pipeline set, because Terraform ignores their task definition revision.

## Rolling back

Re-run the workflow on an older commit's code, or point one service at an earlier image:

```bash
infra/aws/deploy-service.sh <cluster> identity-service <registry>/<repository>:identity-service-<older-commit>
```

The last 8 images of each service are kept.
