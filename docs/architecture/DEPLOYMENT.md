# Deployment architecture

Region ap-south-1 (Mumbai). Everything below is Terraform in `infra/aws/*.tf`; the pipeline is `.github/workflows/deploy.yml`.

```mermaid
flowchart TB
  U[Users] --> CF[CloudFront]
  CF -->|static| S3[(S3 frontend bucket<br/>8 apps)]
  CF -->|/api/*| ALB[Application Load Balancer]
  subgraph VPC
    subgraph Public subnets
      ALB
      subgraph ECS[ECS Fargate cluster - public IPs, no NAT cost]
        GW[gateway]
        ID[identity]
        LE[leasing]
        BI[billing]
        MA[maintenance]
        WF[workflow]
        AI[ai]
        DO[document]
      end
    end
    subgraph Private subnets
      RDS[(RDS PostgreSQL 16)]
    end
  end
  ALB --> GW --> ID & LE & BI & MA & WF & AI & DO
  ID & LE & BI & MA & WF & AI & DO --> RDS
  DO --> FILES[(S3 documents)]
  SM[Secrets Manager] -.JWT, internal token, DB.-> ECS
  ECS -.logs.-> CWL[CloudWatch Logs]
  CWA[CloudWatch alarms<br/>ALB 5xx, gateway unhealthy,<br/>service memory, RDS CPU] --> SNS[SNS topic] --> MAIL[Email]
  ECR[(ECR: one repo per service)] --> ECS
```

## Delivery pipeline

```mermaid
flowchart LR
  PR[Pull request] --> CI[ci.yml: what changed -> backend verify + 90% gate, frontend tests/build]
  CI --> MERGE[merge]
  PUSH[push to develop] --> CH[changes.sh: units changed since deployed/* tag]
  CH --> BT[test + build changed services]
  BT --> DI[deploy identity first]
  DI --> DW[deploy workers]
  DW --> DG[deploy gateway last]
  CH --> FE[build changed frontend apps -> S3 -> invalidate only their paths]
  DG & FE --> SM[smoke: API and site return 200 through CloudFront]
  SM --> TAG[move deployed/<unit> tags]
```

* **Network**: ALB and Fargate tasks sit in public subnets (cost-driven demo choice, no NAT gateway); task security groups accept traffic only from the ALB and from each other; the database is in private subnets. Production hardening: move tasks to private subnets behind NAT/VPC endpoints.
* **Auth to AWS: GitHub OIDC**, short-lived role session; there are no AWS keys in GitHub.
* **Changed-units-only**: the `deployed/<unit>` git tag records the last successful deploy of each service/app; a change to shared code rebuilds its dependents.
* **One deploy at a time** (`concurrency: deploy-aws`); queued pushes compute their diff from the last successful deploy, so nothing is skipped.
* **Flyway** runs at service start-up, so a new migration is applied by the rollout that needs it.
* **Rollback**: redeploy a previous image tag (`infra/aws/deploy-service.sh`, `redeploy.ps1`); migrations are forward-only and written additive.
* Also in the repo: `infra/docker` and `docker-compose*.yml` for local/all-in-one, and `infra/k8s` manifests.

## Environments

| | Local | Demo (AWS) |
| --- | --- | --- |
| Run | `docker compose` or `mvn` + Vite | ECS Fargate + CloudFront |
| DB | Postgres container | RDS PostgreSQL 16 (single-AZ for the demo; `multi_az` is one variable away) |
| Secrets | `.env.local` (git-ignored) | Secrets Manager |
