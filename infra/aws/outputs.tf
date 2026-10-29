output "app_url" {
  description = "Open this: the web app and the API on one HTTPS address."
  value       = "https://${aws_cloudfront_distribution.main.domain_name}"
}

output "alb_url" {
  description = "Raw load balancer address. Reachable only when restrict_alb_to_cloudfront = false."
  value       = "http://${aws_lb.main.dns_name}"
}

output "ecr_repository_url" {
  value = aws_ecr_repository.backend.repository_url
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "frontend_bucket" {
  value = aws_s3_bucket.frontend.bucket
}

output "cloudfront_distribution_id" {
  value = aws_cloudfront_distribution.main.id
}

output "github_deploy_role_arn" {
  description = "Set as the AWS_ROLE_ARN variable in the GitHub repository."
  value       = aws_iam_role.github_deploy.arn
}

output "github_variables" {
  description = "Paste-ready commands that configure the deploy workflow (see docs/CI-CD.md)."
  value       = <<-EOT
    gh variable set AWS_ROLE_ARN --body ${aws_iam_role.github_deploy.arn}
    gh variable set AWS_REGION --body ${var.region}
    gh variable set ECS_CLUSTER --body ${aws_ecs_cluster.main.name}
    gh variable set ECR_REPOSITORY --body ${aws_ecr_repository.backend.name}
    gh variable set FRONTEND_BUCKET --body ${aws_s3_bucket.frontend.bucket}
    gh variable set CLOUDFRONT_DISTRIBUTION_ID --body ${aws_cloudfront_distribution.main.id}
    gh variable set APP_URL --body https://${aws_cloudfront_distribution.main.domain_name}
  EOT
}

output "demo_logins" {
  description = "Where to find the demo personas once DEMO_MODE is on."
  value       = "https://${aws_cloudfront_distribution.main.domain_name}/api/identity/public/demo-users"
}

output "files_bucket" {
  description = "Private bucket holding uploaded documents and photos."
  value       = aws_s3_bucket.files.bucket
}
