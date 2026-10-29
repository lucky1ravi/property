#!/usr/bin/env bash
# Rolls one ECS service onto a new image: copies its current task definition, swaps the image, registers a
# new revision, updates the service and waits until it is stable. ECS's deployment circuit breaker rolls the
# service back on its own when the new tasks fail their health checks, in which case this script exits non-zero.
#
# Usage: deploy-service.sh <cluster> <service> <image-uri>
set -euo pipefail

cluster="$1"; service="$2"; image="$3"

current=$(aws ecs describe-services --cluster "$cluster" --services "$service" --query 'services[0].taskDefinition' --output text)
echo "[$service] current task definition: $current"

aws ecs describe-task-definition --task-definition "$current" --query 'taskDefinition' > "/tmp/$service-td.json"
jq --arg image "$image" '
  .containerDefinitions[0].image = $image
  | del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities, .registeredAt, .registeredBy)
' "/tmp/$service-td.json" > "/tmp/$service-new.json"

new=$(aws ecs register-task-definition --cli-input-json "file:///tmp/$service-new.json" --query 'taskDefinition.taskDefinitionArn' --output text)
echo "[$service] registered: $new"

aws ecs update-service --cluster "$cluster" --service "$service" --task-definition "$new" >/dev/null
echo "[$service] waiting for the rollout to settle..."
if ! aws ecs wait services-stable --cluster "$cluster" --services "$service"; then
  echo "[$service] rollout did not become stable; ECS rolls back automatically on failed health checks." >&2
  aws ecs describe-services --cluster "$cluster" --services "$service" --query 'services[0].events[:5].message' --output text >&2 || true
  exit 1
fi

# The circuit breaker may have rolled back and still reported stable: confirm the new revision is the one running.
running=$(aws ecs describe-services --cluster "$cluster" --services "$service" --query 'services[0].deployments[?status==`PRIMARY`].taskDefinition | [0]' --output text)
if [ "$running" != "$new" ]; then
  echo "[$service] rolled back to $running; the new revision failed its health checks." >&2
  exit 1
fi
echo "[$service] deployed $image"
