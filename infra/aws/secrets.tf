# Generated once and kept in Secrets Manager; tasks receive them as injected environment variables.
# They also live in the Terraform state, so keep the state private (see docs/AWS-DEPLOY.md).

resource "random_password" "db" {
  length  = 32
  special = false
}

resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "random_password" "internal" {
  length  = 40
  special = false
}

resource "aws_secretsmanager_secret" "app" {
  name                    = "${local.name}/app"
  description             = "JWT signing secret, service-to-service token and database password."
  recovery_window_in_days = 0 # demo: allow immediate delete on destroy
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id
  secret_string = jsonencode({
    JWT_SECRET     = random_password.jwt.result
    INTERNAL_TOKEN = random_password.internal.result
    PGPASSWORD     = random_password.db.result
  })
}
