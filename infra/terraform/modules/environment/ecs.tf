# ADR-0023 point 9: one Fargate cluster per environment. One backend image for api, worker and the migration task;
# the process role is chosen with SPRING_PROFILES_ACTIVE (ADR-0023 point 2).

resource "aws_ecs_cluster" "this" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

resource "aws_cloudwatch_log_group" "this" {
  for_each = local.family_secrets

  name              = "/ecs/${local.name}/${each.key}"
  retention_in_days = var.log_retention_days
}

locals {
  db_host = aws_db_instance.this.address

  secret = { for key, secret in aws_secretsmanager_secret.this : key => secret.arn }

  log_options = {
    for family in keys(local.family_secrets) : family => {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this[family].name
        awslogs-region        = local.region
        awslogs-stream-prefix = family
      }
    }
  }

  backend_environment = [
    { name = "DB_URL", value = "jdbc:postgresql://${local.db_host}:5432/operator?sslmode=require" },
    { name = "OIDC_ISSUER_URI", value = local.oidc_issuer },
    { name = "TEMPORAL_ADDRESS", value = var.temporal_address },
    { name = "TEMPORAL_NAMESPACE", value = var.temporal_namespace },
    { name = "WEBHOOK_BASE_URL", value = "https://${var.api_host}" },
    { name = "OPERATOR_APP_BASE_URL", value = "https://${var.app_host}" },
    { name = "OPERATOR_NOTIFICATIONS_MAILDOMAIN", value = var.mail_domain },
    { name = "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", value = "http://localhost:4318/v1/metrics" },
    { name = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", value = "http://localhost:4318/v1/traces" },
  ]

  backend_secrets = concat(
    [
      { name = "DB_USERNAME", valueFrom = "${local.secret["db-runtime"]}:username::" },
      { name = "DB_PASSWORD", valueFrom = "${local.secret["db-runtime"]}:password::" },
      { name = "TEMPORAL_API_KEY", valueFrom = local.secret["temporal-api-key"] },
      { name = "INTEGRATION_SECRETS_KEY", valueFrom = "${local.secret["integration-secrets-key"]}:key::" },
      { name = "INTEGRATION_SECRETS_KEY_ID", valueFrom = "${local.secret["integration-secrets-key"]}:key_id::" },
    ],
    var.direct_anthropic_api_enabled ? [
      { name = "ANTHROPIC_API_KEY", valueFrom = local.secret["anthropic-api-key"] },
    ] : [],
  )

  # The collector receives OTLP on localhost and writes metrics as EMF to CloudWatch and traces to X-Ray.
  collector = {
    name        = "otel-collector"
    image       = var.adot_image
    essential   = true
    command     = ["--config=/etc/ecs/ecs-default-config.yaml"]
    cpu         = 64
    memory      = 128
    environment = []
  }

  # Container definitions as JSON per family; the families differ in shape, so they are encoded here.
  backend_containers = {
    for role in ["api", "worker"] : role => jsonencode([
      {
        name         = role
        image        = local.backend_image
        essential    = true
        portMappings = [for port in (role == "api" ? [8080] : []) : { containerPort = port, protocol = "tcp" }]
        environment  = concat(local.backend_environment, [{ name = "SPRING_PROFILES_ACTIVE", value = "cloud,${role}" }])
        secrets      = local.backend_secrets
        dependsOn    = [{ containerName = "otel-collector", condition = "START" }]
        logConfiguration = local.log_options[role]
      },
      merge(local.collector, { logConfiguration = local.log_options[role] }),
    ])
  }

  containers = merge(local.backend_containers, {
    frontend = jsonencode([{
      name         = "frontend"
      image        = local.frontend_image
      essential    = true
      portMappings = [{ containerPort = 3000, protocol = "tcp" }]
      environment = [
        { name = "APP_URL", value = "https://${var.app_host}" },
        { name = "BACKEND_URL", value = "https://${var.api_host}" },
        { name = "OIDC_ISSUER", value = local.oidc_issuer },
        { name = "OIDC_CLIENT_ID", value = "operator-web" },
      ]
      secrets = [
        { name = "OIDC_CLIENT_SECRET", valueFrom = "${local.secret["frontend"]}:oidc_client_secret::" },
        { name = "SESSION_SECRET", valueFrom = "${local.secret["frontend"]}:session_secret::" },
      ]
      logConfiguration = local.log_options["frontend"]
    }])
    keycloak = jsonencode([{
      name         = "keycloak"
      image        = var.keycloak_image
      essential    = true
      command      = ["start"]
      portMappings = [{ containerPort = 8080, protocol = "tcp" }, { containerPort = 9000, protocol = "tcp" }]
      environment = [
        { name = "KC_DB", value = "postgres" },
        { name = "KC_DB_URL", value = "jdbc:postgresql://${local.db_host}:5432/keycloak?sslmode=require" },
        { name = "KC_HOSTNAME", value = "https://${var.auth_host}" },
        { name = "KC_HTTP_ENABLED", value = "true" },
        { name = "KC_PROXY_HEADERS", value = "xforwarded" },
        { name = "KC_HEALTH_ENABLED", value = "true" },
      ]
      secrets = [
        { name = "KC_DB_USERNAME", valueFrom = "${local.secret["keycloak-db"]}:username::" },
        { name = "KC_DB_PASSWORD", valueFrom = "${local.secret["keycloak-db"]}:password::" },
        { name = "KC_BOOTSTRAP_ADMIN_USERNAME", valueFrom = "${local.secret["keycloak-admin"]}:username::" },
        { name = "KC_BOOTSTRAP_ADMIN_PASSWORD", valueFrom = "${local.secret["keycloak-admin"]}:password::" },
      ]
      logConfiguration = local.log_options["keycloak"]
    }])
    # ADR-0023 point 3: run once per rollout, before the services; a non-zero exit stops the rollout.
    migrate = jsonencode([{
      name      = "migrate"
      image     = local.backend_image
      essential = true
      command = [
        "java", "-Dloader.main=com.ecommerceagent.MigrateCommand", "-cp", "/app/app.jar",
        "org.springframework.boot.loader.launch.PropertiesLauncher",
      ]
      environment = [
        { name = "DB_URL", value = "jdbc:postgresql://${local.db_host}:5432/operator?sslmode=require" },
      ]
      secrets = [
        { name = "DB_MIGRATION_USERNAME", valueFrom = "${local.secret["db-migrator"]}:username::" },
        { name = "DB_MIGRATION_PASSWORD", valueFrom = "${local.secret["db-migrator"]}:password::" },
      ]
      logConfiguration = local.log_options["migrate"]
    }])
    # Administration only (bootstrapping the roles, logical dumps); run by hand with a command override.
    db-admin = jsonencode([{
      name        = "db-admin"
      image       = "public.ecr.aws/docker/library/postgres:17"
      essential   = true
      command     = ["psql", "--version"]
      environment = [
        { name = "PGHOST", value = local.db_host },
        { name = "PGDATABASE", value = "operator" },
        { name = "PGSSLMODE", value = "require" },
        { name = "DUMP_BUCKET", value = aws_s3_bucket.dumps.bucket },
      ]
      secrets = [
        { name = "PGUSER", valueFrom = "${aws_db_instance.this.master_user_secret[0].secret_arn}:username::" },
        { name = "PGPASSWORD", valueFrom = "${aws_db_instance.this.master_user_secret[0].secret_arn}:password::" },
      ]
      logConfiguration = local.log_options["db-admin"]
    }])
  })

  task_size = merge(
    { for name, service in var.services : name => { cpu = service.cpu, memory = service.memory } },
    {
      migrate  = { cpu = 512, memory = 1024 }
      db-admin = { cpu = 256, memory = 512 }
    },
  )
}

resource "aws_ecs_task_definition" "this" {
  for_each = local.containers

  family                   = "${local.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = local.task_size[each.key].cpu
  memory                   = local.task_size[each.key].memory
  execution_role_arn       = aws_iam_role.execution[each.key].arn
  task_role_arn            = aws_iam_role.task[each.key].arn
  container_definitions    = each.value

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
}

resource "aws_ecs_service" "this" {
  for_each = var.services

  name                   = each.key
  cluster                = aws_ecs_cluster.this.id
  task_definition        = aws_ecs_task_definition.this[each.key].arn
  desired_count          = each.value.desired_count
  enable_execute_command = false
  propagate_tags         = "SERVICE"

  capacity_provider_strategy {
    capacity_provider = var.use_fargate_spot ? "FARGATE_SPOT" : "FARGATE"
    weight            = 1
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = true
  }

  dynamic "load_balancer" {
    for_each = contains(keys(local.routed), each.key) ? [each.key] : []

    content {
      target_group_arn = aws_lb_target_group.this[load_balancer.value].arn
      container_name   = load_balancer.value
      container_port   = local.routed[load_balancer.value].port
    }
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  depends_on = [aws_lb_listener_rule.this, aws_ecs_cluster_capacity_providers.this]
}
