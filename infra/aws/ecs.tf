# One cluster, one Fargate service per microservice. All run the same image; SERVICE selects the jar.
# Services find each other through Cloud Map DNS (<name>.propertyos.local).

locals {
  services = {
    identity-service    = { port = 8081, url_env = "IDENTITY_URL", dns = "identity" }
    leasing-service     = { port = 8082, url_env = "LEASING_URL", dns = "leasing" }
    billing-service     = { port = 8083, url_env = "BILLING_URL", dns = "billing" }
    maintenance-service = { port = 8084, url_env = "MAINTENANCE_URL", dns = "maintenance" }
    workflow-service    = { port = 8085, url_env = "WORKFLOW_URL", dns = "workflow" }
    ai-service          = { port = 8086, url_env = "AI_URL", dns = "ai" }
    document-service    = { port = 8087, url_env = "DOCUMENTS_URL", dns = "documents" }
    api-gateway         = { port = 8080, url_env = null, dns = "gateway" }
  }

  # Identity creates the shared platform schema, so it starts first; the gateway starts last.
  workers = { for k, v in local.services : k => v if k != "identity-service" && k != "api-gateway" }

  image = "${aws_ecr_repository.backend.repository_url}:${var.image_tag}"

  downstream_urls = {
    for k, v in local.services : v.url_env => "http://${v.dns}.${aws_service_discovery_private_dns_namespace.main.name}:${v.port}" if v.url_env != null
  }

  common_env = merge(
    {
      PGHOST                            = aws_db_instance.main.address
      PGPORT                            = "5432"
      PGDATABASE                        = "propertyos"
      PGUSER                            = "propertyos"
      PGSSL                             = "?sslmode=require"
      DB_POOL_SIZE                      = tostring(var.db_pool_size)
      DEMO_MODE                         = tostring(var.demo_mode)
      ALLOWED_ORIGINS                   = "https://${aws_cloudfront_distribution.main.domain_name}"
      JAVA_OPTS                         = "-XX:+UseSerialGC -Xss512k -XX:MaxRAMPercentage=70"
      LOGGING_STRUCTURED_FORMAT_CONSOLE = "ecs"
      AWS_REGION                        = var.region
    },
    local.downstream_urls,
    var.ai_provider == "" ? {} : { AI_PROVIDER = var.ai_provider },
  )
}

resource "aws_ecs_cluster" "main" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = var.container_insights ? "enabled" : "disabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

resource "aws_service_discovery_private_dns_namespace" "main" {
  name = "propertyos.local"
  vpc  = aws_vpc.main.id
}

resource "aws_service_discovery_service" "svc" {
  for_each      = local.services
  name          = each.value.dns
  force_destroy = true
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.main.id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
  health_check_custom_config {}
}

resource "aws_cloudwatch_log_group" "svc" {
  for_each          = local.services
  name              = "/ecs/${local.name}/${each.key}"
  retention_in_days = var.log_retention_days
}

resource "aws_ecs_task_definition" "svc" {
  for_each                 = local.services
  family                   = "${local.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = lookup(var.service_cpu, each.key, var.task_cpu)
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name         = each.key
    image        = local.image
    essential    = true
    portMappings = [{ containerPort = each.value.port, protocol = "tcp" }]
    environment  = [for k, v in merge(local.common_env, { SERVICE = each.key, PORT = tostring(each.value.port) }) : { name = k, value = v }]
    secrets = [
      { name = "JWT_SECRET", valueFrom = "${aws_secretsmanager_secret.app.arn}:JWT_SECRET::" },
      { name = "INTERNAL_TOKEN", valueFrom = "${aws_secretsmanager_secret.app.arn}:INTERNAL_TOKEN::" },
      { name = "PGPASSWORD", valueFrom = "${aws_secretsmanager_secret.app.arn}:PGPASSWORD::" },
    ]
    healthCheck = {
      command     = ["CMD-SHELL", "curl -fs http://localhost:${each.value.port}/actuator/health/readiness || exit 1"]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 90
    }
    stopTimeout = 35 # a little longer than the 30 s graceful shutdown
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.svc[each.key].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "ecs"
      }
    }
  }])
}

locals {
  service_defaults = {
    cluster                = aws_ecs_cluster.main.id
    desired_count          = var.desired_count
    launch_type            = "FARGATE"
    enable_execute_command = true
  }
}

# Identity first. Waiting for it to be steady means the platform schema exists before the others start.
resource "aws_ecs_service" "identity" {
  name                   = "identity-service"
  cluster                = local.service_defaults.cluster
  task_definition        = aws_ecs_task_definition.svc["identity-service"].arn
  desired_count          = local.service_defaults.desired_count
  launch_type            = var.use_spot ? null : "FARGATE"
  enable_execute_command = local.service_defaults.enable_execute_command
  wait_for_steady_state  = true

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  dynamic "capacity_provider_strategy" {
    for_each = var.use_spot ? [1] : []
    content {
      capacity_provider = "FARGATE_SPOT"
      weight            = 1
    }
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }
  service_registries {
    registry_arn = aws_service_discovery_service.svc["identity-service"].arn
  }

  # The pipeline registers new task definition revisions; Terraform must not roll them back.
  lifecycle {
    ignore_changes = [task_definition]
  }
  depends_on = [aws_db_instance.main, aws_ecs_cluster_capacity_providers.main]
}

resource "aws_ecs_service" "worker" {
  for_each               = local.workers
  name                   = each.key
  cluster                = local.service_defaults.cluster
  task_definition        = aws_ecs_task_definition.svc[each.key].arn
  desired_count          = local.service_defaults.desired_count
  launch_type            = var.use_spot ? null : "FARGATE"
  enable_execute_command = local.service_defaults.enable_execute_command

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  dynamic "capacity_provider_strategy" {
    for_each = var.use_spot ? [1] : []
    content {
      capacity_provider = "FARGATE_SPOT"
      weight            = 1
    }
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }
  service_registries {
    registry_arn = aws_service_discovery_service.svc[each.key].arn
  }
  lifecycle {
    ignore_changes = [task_definition]
  }
  depends_on = [aws_ecs_service.identity]
}

resource "aws_ecs_service" "gateway" {
  name                   = "api-gateway"
  cluster                = local.service_defaults.cluster
  task_definition        = aws_ecs_task_definition.svc["api-gateway"].arn
  desired_count          = local.service_defaults.desired_count
  launch_type            = var.use_spot ? null : "FARGATE"
  enable_execute_command = local.service_defaults.enable_execute_command

  health_check_grace_period_seconds  = 120
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  dynamic "capacity_provider_strategy" {
    for_each = var.use_spot ? [1] : []
    content {
      capacity_provider = "FARGATE_SPOT"
      weight            = 1
    }
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.gateway.arn
    container_name   = "api-gateway"
    container_port   = 8080
  }
  service_registries {
    registry_arn = aws_service_discovery_service.svc["api-gateway"].arn
  }
  lifecycle {
    ignore_changes = [task_definition]
  }
  depends_on = [aws_lb_listener.http, aws_ecs_service.worker]
}
