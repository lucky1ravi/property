variable "region" {
  description = "AWS region. Mumbai by default."
  type        = string
  default     = "ap-south-1"
}

variable "environment" {
  description = "Short environment name used in resource names and tags."
  type        = string
  default     = "demo"
}

variable "image_tag" {
  description = "Tag of the backend image in ECR that every service runs. The pipeline sets this to the git SHA."
  type        = string
  default     = "latest"
}

variable "demo_mode" {
  description = "DEMO_MODE for the backend: lets the demo personas sign in without a password. Turn off for anything real."
  type        = bool
  default     = true
}

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "db_instance_class" {
  description = "RDS instance size. t4g.micro is enough for the demo; use db.t4g.small for live load tests."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_pool_size" {
  description = "Hikari pool size per service (DB_POOL_SIZE)."
  type        = number
  default     = 3
}

variable "desired_count" {
  description = "Tasks per service. 1 = cut-down demo; 2 or more spreads across both zones for high availability."
  type        = number
  default     = 1
}

variable "task_cpu" {
  description = "Fargate CPU units per service task (512 = 0.5 vCPU)."
  type        = number
  default     = 512
}

variable "task_memory" {
  description = "Fargate memory in MiB per service task. Spring Boot needs about 1 GiB."
  type        = number
  default     = 1024
}

variable "log_retention_days" {
  type    = number
  default = 14
}

variable "alarm_email" {
  description = "Optional e-mail address subscribed to the CloudWatch alarm topic. Leave empty to skip."
  type        = string
  default     = ""
}

variable "github_repo" {
  description = "owner/repo allowed to assume the deploy role through GitHub OIDC."
  type        = string
  default     = "lucky1ravi/property"
}

# GitHub now puts the numeric owner and repository ids into the OIDC subject (repo:owner@<id>/repo@<id>:ref:...), so a
# deleted and re-created account or repository with the same name can never take over this role.
# Find yours with:  gh api repos/<owner>/<repo> --jq '{repo_id: .id, owner_id: .owner.id}'
variable "github_owner_id" {
  description = "Numeric id of the GitHub account that owns the repository."
  type        = string
  default     = "1227451"
}

variable "github_repo_id" {
  description = "Numeric id of the GitHub repository."
  type        = string
  default     = "1437059385"
}

variable "create_github_oidc_provider" {
  description = "Create the GitHub OIDC identity provider. Set false if the account already has one (only one is allowed per account)."
  type        = bool
  default     = true
}

variable "ai_provider" {
  description = "AI_PROVIDER for the ai service: empty = offline rule engine, 'bedrock' or 'openai'. Bedrock uses the task role, so no key is stored."
  type        = string
  default     = "bedrock"
}

variable "bedrock_region" {
  description = "BEDROCK_REGION: the region the AI service calls Bedrock in. The default models are not offered in every region (the rest of the stack runs in var.region), and the vision model is a US cross-region profile."
  type        = string
  default     = "us-east-1"
}

variable "bedrock_model_id" {
  description = "BEDROCK_MODEL_ID: the Bedrock text model for the assistant and review moderation. Used only when ai_provider is 'bedrock'."
  type        = string
  default     = "openai.gpt-oss-120b-1:0"
}

variable "bedrock_vision_model_id" {
  description = "BEDROCK_VISION_MODEL_ID: the Bedrock model for photo checks. Empty = photo checks use the built-in simulator."
  type        = string
  default     = "us.amazon.nova-pro-v1:0"
}

variable "restrict_alb_to_cloudfront" {
  description = "true = the ALB only accepts traffic from CloudFront (recommended). false = open to the internet, so the raw ALB URL also works."
  type        = bool
  default     = true
}

variable "use_spot" {
  description = "Run tasks on Fargate Spot: about 70% cheaper, but AWS can reclaim a task with 2 minutes' notice (it is replaced automatically, with a short gap for a one-task service)."
  type        = bool
  default     = false
}

variable "service_cpu" {
  description = "Per-service CPU override, e.g. { ai-service = 256 }. Valid Fargate pairs apply: 256 CPU needs 512, 1024 or 2048 MiB of memory."
  type        = map(number)
  default     = {}
}

variable "container_insights" {
  description = "Detailed per-task CloudWatch metrics. They are billed per metric, so they are off by default."
  type        = bool
  default     = false
}

variable "email_provider" {
  description = "How the workflow service sends email: 'ses' (Amazon SES) or 'none' (only logs)."
  type        = string
  default     = "ses"
}

variable "email_from" {
  description = "Sender address of every email. It is created as an SES identity and AWS sends it a verification link to open once."
  type        = string
  default     = "ravindra.nalawade56@gmail.com"
}

variable "email_override_to" {
  description = "While set, every email goes to this one address instead of its real recipient (the message says who it was meant for). Empty = send to the real recipients, which needs SES production access."
  type        = string
  default     = "ravindra.nalawade56@gmail.com"
}
