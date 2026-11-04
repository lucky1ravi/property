# One-time setup for the Terraform pipeline (.github/workflows/terraform.yml). It creates what the pipeline needs BEFORE it can run, so it is applied by
# hand once, from a laptop, and keeps its own small local state:
#   - an S3 bucket and a DynamoDB table that hold the infrastructure's state and lock it, so two runs never change it at once;
#   - a read-only role for pull requests (plan) and a full-access role for applying, which only a GitHub environment with a required reviewer can assume.
# See docs/TERRAFORM-PIPELINE.md.

terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.70" }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { Project = "propertyos", ManagedBy = "terraform", Stack = "bootstrap" }
  }
}

variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "name" {
  description = "Prefix for the names created here."
  type        = string
  default     = "propertyos"
}

variable "github_repo" {
  description = "owner/repo allowed to assume the roles through GitHub OIDC."
  type        = string
  default     = "lucky1ravi/property"
}

variable "github_owner_id" {
  description = "Numeric id of the GitHub account that owns the repository (gh api repos/<owner>/<repo> --jq .owner.id)."
  type        = string
  default     = "1227451"
}

variable "github_repo_id" {
  description = "Numeric id of the GitHub repository (gh api repos/<owner>/<repo> --jq .id)."
  type        = string
  default     = "1437059385"
}

variable "apply_environment" {
  description = "Name of the GitHub environment whose runs may apply. Give it a required reviewer so a person approves every apply."
  type        = string
  default     = "aws-infra"
}

data "aws_caller_identity" "current" {}

# The GitHub OIDC provider already exists: the main stack (infra/aws) creates it.
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

locals {
  bucket  = "${var.name}-tfstate-${data.aws_caller_identity.current.account_id}"
  table   = "${var.name}-tfstate-lock"
  subject = "repo:${split("/", var.github_repo)[0]}@${var.github_owner_id}/${split("/", var.github_repo)[1]}@${var.github_repo_id}"
}

# ---- state ----

resource "aws_s3_bucket" "state" {
  bucket = local.bucket
  # State holds secrets such as the database password. Never delete it by accident.
  lifecycle { prevent_destroy = true }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_dynamodb_table" "lock" {
  name         = local.table
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "LockID"
  attribute {
    name = "LockID"
    type = "S"
  }
}

# ---- roles for GitHub Actions ----

data "aws_iam_policy_document" "plan_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${local.subject}:pull_request"]
    }
  }
}

data "aws_iam_policy_document" "apply_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [data.aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["${local.subject}:environment:${var.apply_environment}"]
    }
  }
}

# A plan reads everything and writes only to the state lock, so a pull request can never change infrastructure.
resource "aws_iam_role" "plan" {
  name               = "${var.name}-terraform-plan"
  assume_role_policy = data.aws_iam_policy_document.plan_assume.json
}

resource "aws_iam_role_policy_attachment" "plan_read" {
  role       = aws_iam_role.plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

resource "aws_iam_role_policy" "plan_state" {
  name = "state"
  role = aws_iam_role.plan.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:ListBucket", "s3:GetObject"], Resource = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"] },
      { Effect = "Allow", Action = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem"], Resource = aws_dynamodb_table.lock.arn },
      # Refreshing the app secret reads its value. The state this role can already read holds the same values.
      { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = "arn:aws:secretsmanager:${var.region}:${data.aws_caller_identity.current.account_id}:secret:${var.name}-*/app-*" },
    ]
  })
}

# Applying creates networks, databases, roles and so on, so this role is broad. It can only be assumed by a run that a reviewer approved.
resource "aws_iam_role" "apply" {
  name               = "${var.name}-terraform-apply"
  assume_role_policy = data.aws_iam_policy_document.apply_assume.json
}

resource "aws_iam_role_policy_attachment" "apply_admin" {
  role       = aws_iam_role.apply.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}

output "state_bucket" {
  value = aws_s3_bucket.state.bucket
}

output "lock_table" {
  value = aws_dynamodb_table.lock.name
}

output "github_variables" {
  description = "Paste-ready commands that configure the Terraform workflow (see docs/TERRAFORM-PIPELINE.md)."
  value       = <<-EOT
    gh variable set TF_STATE_BUCKET --body ${aws_s3_bucket.state.bucket}
    gh variable set TF_LOCK_TABLE --body ${aws_dynamodb_table.lock.name}
    gh variable set TF_PLAN_ROLE_ARN --body ${aws_iam_role.plan.arn}
    gh variable set TF_APPLY_ROLE_ARN --body ${aws_iam_role.apply.arn}
    gh variable set AWS_REGION --body ${var.region}
  EOT
}
