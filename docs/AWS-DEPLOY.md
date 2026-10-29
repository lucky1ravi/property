# Deploying PropertyOS to AWS (ECS Fargate, Mumbai)

A cut-down demo of the production topology: the eight services run as separate Fargate tasks, the database is a
single PostgreSQL instance, and one HTTPS address serves both the web app and the API.

```
                       Browser
                          │ https
                   ┌──────▼───────┐   /            ┌───────────────┐
                   │  CloudFront  ├───────────────▶│ S3 (frontend) │
                   └──────┬───────┘                └───────────────┘
                          │ /api/*  (http, CloudFront IPs only)
                   ┌──────▼───────┐
                   │     ALB      │  public subnets, 2 zones
                   └──────┬───────┘
        ┌─────────────────▼──────────────────┐
        │ ECS Fargate (security group "tasks")│  one task per service
        │  api-gateway :8080 (only ALB target)│
        │  identity 8081  leasing 8082        │  find each other at
        │  billing 8083   maintenance 8084    │  <name>.propertyos.local
        │  workflow 8085  ai 8086  documents 8087
        └─────────────────┬──────────────────┘
                          │ 5432, TLS
                   ┌──────▼───────┐
                   │ RDS Postgres │  private subnets
                   └──────────────┘
Secrets Manager: JWT_SECRET, INTERNAL_TOKEN, database password → injected as environment variables
CloudWatch: one log group per service, alarms → SNS
```

Everything is in `infra/aws/` (Terraform) and `.github/workflows/deploy.yml` (pipeline).

## What you need

An AWS account with admin rights for the first run, plus [Terraform](https://developer.hashicorp.com/terraform/install) 1.6+,
the [AWS CLI](https://aws.amazon.com/cli/) v2, Docker and the [GitHub CLI](https://cli.github.com/) (`gh`).
Run `aws configure` (or `aws sso login`) so the CLI points at your account.

New accounts have a low Fargate quota. The demo runs 8 tasks of 0.5 vCPU (4 vCPU) and a rollout briefly doubles that,
so check *Service Quotas → Fargate On-Demand vCPU* is at least 8.

## First deployment

The services cannot start until an image exists in ECR, so the first run is in three parts.

```powershell
cd infra/aws
copy terraform.tfvars.example terraform.tfvars      # edit if you want (alarm e-mail, desired_count, ...)
terraform init

# 1. Create the image registry (and nothing else yet)
terraform apply -target=aws_ecr_repository.backend

# 2. Build and push the backend image
$repo = terraform output -raw ecr_repository_url
aws ecr get-login-password --region ap-south-1 | docker login --username AWS --password-stdin $repo.Split('/')[0]
docker build -t "${repo}:latest" ../../backend
docker push "${repo}:latest"

# 3. Everything else: network, database, services, load balancer, CloudFront (about 15 minutes)
terraform apply
```

If `terraform apply` fails with *EntityAlreadyExists* for the GitHub identity provider, your account already has one:
set `create_github_oidc_provider = false` in `terraform.tfvars` and apply again.

`identity-service` is created first and Terraform waits for it to be healthy, because it creates the shared
`platform` schema the other services rely on. The rest start once it is up.

### Publish the frontend

The frontend is built with the CloudFront address baked in, so it can only be built after the first apply.

```powershell
terraform output -raw github_variables     # copy and run the lines it prints (sets the repository variables)
```

Then either run the pipeline (GitHub → Actions → *Deploy to AWS* → Run workflow, untick *backend*), or do it by hand:

```powershell
cd ../../frontend
$env:VITE_API_URL = terraform -chdir=../infra/aws output -raw app_url
npm ci; npm run build
aws s3 sync dist s3://$(terraform -chdir=../infra/aws output -raw frontend_bucket) --delete
aws cloudfront create-invalidation --distribution-id $(terraform -chdir=../infra/aws output -raw cloudfront_distribution_id) --paths "/*"
```

Open `terraform output app_url`. The demo personas appear on the sign-in page because `DEMO_MODE=true`.

## Checking it works

```powershell
$url = terraform output -raw app_url
curl "$url/api/identity/public/demo-users"          # 200 and a list of personas
aws ecs describe-services --cluster propertyos-demo --services api-gateway identity-service --query "services[].[serviceName,runningCount,desiredCount]"
aws logs tail /ecs/propertyos-demo/api-gateway --follow
```

Run the performance smoke test against the live address:

```powershell
cd performance
mvn gatling:test "-DbaseUrl=$url" -Dusers=3 -DrampSeconds=10 -DholdSeconds=20
```

The gateway rate limits public paths to 120 requests a minute per client address. Keep the smoke test small, or
raise `RATE_LIMIT_PUBLIC_PER_MINUTE` while testing.

## Day-to-day: the pipeline

*Deploy to AWS* is a manual workflow on `develop` or `main`. It signs in to AWS through OIDC (no stored keys), then:

1. builds the image and pushes it as `:<git sha>` and `:latest`;
2. rolls out `identity-service`, then the six workers in parallel, then `api-gateway`;
3. checks the API answers through CloudFront;
4. builds and uploads the frontend, then invalidates the CloudFront cache;
5. optionally runs the Gatling smoke test.

**Rollback.** Each service uses the ECS deployment circuit breaker. If the new tasks fail their health checks
(`/actuator/health/readiness`), ECS stops the rollout and returns to the previous task definition on its own, and
`infra/aws/deploy-service.sh` exits non-zero so the pipeline turns red. To go back by hand, run the workflow
again from the commit you want, or `aws ecs update-service --task-definition <earlier revision>`.

Terraform owns the infrastructure and the first task definitions; the pipeline owns every later revision
(`ignore_changes = [task_definition]`), so a `terraform apply` will not undo a deployment.

## What it costs

Roughly **US$7 a day (about US$220 a month)** with the defaults, in Mumbai: eight Fargate tasks at 0.5 vCPU / 1 GiB,
a `db.t4g.micro` database, the load balancer, and a public IPv4 address for each task and for the load balancer.
CloudFront and Secrets Manager are small at demo traffic. Destroy it when the interview is over:

```powershell
cd infra/aws
terraform destroy
```

Cost levers, biggest first (set in `terraform.tfvars`; figures are estimates):

| Lever | Setting | Saves | Trade-off |
|---|---|---|---|
| Fargate Spot | `use_spot = true` | about 70% of the task cost (around US$100 a month) | AWS can reclaim a task with 2 minutes' notice; it is replaced automatically, leaving a short gap. |
| Smaller tasks for light services | `service_cpu = { ai-service = 256, ... }` | about US$8 a month per service | Slower startup and less headroom under load. |
| Switch off when idle | `desired_count = 0`, then apply | nearly all the task cost | The app is down until you set it back to 1. The database keeps running. |
| Destroy after the demo | `terraform destroy` | everything | Data is lost and the next deploy takes about 15 minutes. |

Already applied: no NAT gateway, Container Insights off, `db.t4g.micro`, 14-day log retention, one database for all services.
Use `db_instance_class = "db.t4g.small"` if you will run the load tests live.

## Design choices and limits

| Choice | Reason | Production change |
|---|---|---|
| Tasks in public subnets with public IPs | Avoids a NAT gateway (about US$35 a month plus data). Security groups still allow only the ALB and sibling tasks in. | Private subnets with NAT or VPC endpoints. |
| One CloudFront address for app and API | One HTTPS origin: no CORS, no mixed content, no domain or certificate needed. | Add a Route 53 domain and an ACM certificate. |
| ALB accepts only CloudFront addresses | Keeps the gateway's rate limiting and JWT checks as the only way in. Set `restrict_alb_to_cloudfront = false` to use the raw ALB URL. | HTTPS listener, plus a secret header CloudFront adds. |
| `desired_count = 1` | Cut-down demo. | `desired_count = 2` spreads each service over both zones; add `multi_az = true` for the database and autoscaling. |
| API calls time out at 60 seconds | CloudFront's default origin timeout. AI calls that take longer return 504. | Request a CloudFront quota increase, or call the ALB directly from the app. |
| Secrets generated by Terraform | No manual secret handling. The values are also in the Terraform state file. | Remote state in an encrypted S3 bucket; rotate secrets in Secrets Manager. |
| `DEMO_MODE=true` | Lets the demo personas sign in without a password. The startup secrets check only warns in this mode (the generated secrets are strong anyway). | Set `demo_mode = false`. |

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| Service stuck at 0 running tasks | `aws ecs describe-services ... --query "services[0].events[:5]"`. *CannotPullContainerError*: the image tag is not in ECR yet (step 2). |
| Tasks restart in a loop | `aws logs tail /ecs/propertyos-demo/<service>`. Usually the database: check the RDS instance is *available* and the password secret is unchanged. |
| 502 or 503 from `/api/...` | The gateway has no healthy task, or a downstream circuit breaker is open (the response carries `Retry-After: 10`). |
| Blank page after sign-in | Frontend built without `VITE_API_URL`; rebuild with the CloudFront address and re-upload. |
| Need a shell in a task | `aws ecs execute-command --cluster propertyos-demo --task <id> --container <service> --interactive --command sh` |
