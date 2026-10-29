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

variable "create_github_oidc_provider" {
  description = "Create the GitHub OIDC identity provider. Set false if the account already has one (only one is allowed per account)."
  type        = bool
  default     = true
}

variable "ai_provider" {
  description = "AI_PROVIDER for the ai service: empty = offline rule engine, 'bedrock' or 'openai'."
  type        = string
  default     = ""
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
