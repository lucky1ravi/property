# MANUAL FALLBACK. Day to day, push to the develop branch and GitHub Actions deploys only what changed (docs/CI-CD.md).
# Use this script for the very first deploy, or when GitHub Actions is not available. Note that once the pipeline has
# deployed a service, that service runs a per-commit image tag, so this script's :latest image will not replace it
# unless you start the services from the newest task definition (`terraform apply` creates one pointing at :latest).
#
# Builds the backend image, pushes it, and restarts every service on it, in the right order.
# Run from infra/aws after `terraform apply`:   powershell -ExecutionPolicy Bypass -File .\redeploy.ps1
# Services start from the newest task definition of their family, so settings changed in Terraform are picked up.
$ErrorActionPreference = "Stop"

$repo     = terraform output -raw ecr_repository_url
$cluster  = terraform output -raw ecs_cluster
$region   = "ap-south-1"
$registry = $repo.Split('/')[0]

Write-Host "Logging in to $registry"
$pw = aws ecr get-login-password --region $region
docker login --username AWS --password $pw $registry | Out-Null

Write-Host "Building and pushing $repo`:latest"
docker build -t "${repo}:latest" ../../backend
if ($LASTEXITCODE -ne 0) { throw "docker build failed" }
docker push "${repo}:latest"
if ($LASTEXITCODE -ne 0) { throw "docker push failed" }

function Roll($service) {
    $family = "$cluster-$service"
    Write-Host "Restarting $service on the newest $family"
    aws ecs update-service --cluster $cluster --service $service --task-definition $family --force-new-deployment --region $region | Out-Null
    aws ecs wait services-stable --cluster $cluster --services $service --region $region
    if ($LASTEXITCODE -ne 0) { throw "$service did not become stable. See: aws logs tail /ecs/$cluster/$service --since 15m" }
}

# Identity first (it prepares the shared schema), then the other services, the gateway last.
Roll "identity-service"
foreach ($s in "leasing-service","billing-service","maintenance-service","workflow-service","ai-service","document-service") { Roll $s }
Roll "api-gateway"
Write-Host "Done. Open $(terraform output -raw app_url)"
