# One image, three roles (ADR-021), run as Fargate tasks in the public subnets.
#
# Order on every apply that changes the image: the migrate task runs to completion (exit 0)
# BEFORE either service rolls out — terraform_data.migrate below, which both services depend
# on. A failed migration stops the apply with the old version still serving.

locals {
  image = "${data.aws_ecr_repository.app.repository_url}:${var.image_tag}"
  roles = toset(["api", "worker", "migrate"])

  # Configuration common to all three. Plain values only; secrets are injected separately so
  # they never appear in the task definition (visible to anyone with ecs:Describe*).
  common_environment = [
    { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${aws_db_instance.main.db_name}?sslmode=require" },
    { name = "SPRING_DATA_REDIS_HOST", value = aws_elasticache_replication_group.valkey.primary_endpoint_address },
    { name = "SPRING_DATA_REDIS_PORT", value = "6379" },
    { name = "SPRING_DATA_REDIS_SSL_ENABLED", value = "true" }, # ElastiCache: TLS required (7.3)
    # ADR-023: plain HTTP, so browsers would drop a Secure cookie. Remove with HTTPS.
    { name = "CHESS_AUTH_REFRESH_COOKIE_SECURE", value = "false" },
  ]

  # ECS reads these at task start, from Secrets Manager, into environment variables. The
  # ":key::" suffix picks one field out of the RDS-managed JSON secret.
  common_secrets = [
    { name = "SPRING_DATASOURCE_USERNAME", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:username::" },
    { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" },
    { name = "CHESS_AUTH_JWT_SECRET", valueFrom = aws_secretsmanager_secret.jwt.arn },
  ]

  # Valkey fan-out for api (>1 instance, ADR-002) and worker (it publishes RATING_UPDATED).
  # NOT migrate: with it, the migrate run started a pub/sub subscriber, and the first apply's
  # migration — already applied, all 7 versions — failed on a Valkey connection it never needed.
  # A schema migration must not depend on the cache being reachable.
  fanout = [{ name = "CHESS_REALTIME_FANOUT", value = "valkey" }]

  tasks = {
    api     = { cpu = 512, memory = 1024, profiles = "aws", role_arn = null, extra_env = local.fanout }
    worker  = { cpu = 256, memory = 1024, profiles = "aws,worker", role_arn = aws_iam_role.worker_task.arn, extra_env = local.fanout }
    migrate = { cpu = 256, memory = 1024, profiles = "aws,migrate", role_arn = null, extra_env = [] }
  }
}

resource "aws_cloudwatch_log_group" "task" {
  for_each          = local.roles
  name              = "/ecs/${var.name}/${each.key}"
  retention_in_days = 7 # default is never-expire: storage that bills forever
}

resource "aws_ecs_cluster" "main" {
  name = var.name
  # Container Insights: per-task CPU/memory metrics, billed as custom metrics. Phase 9 decides
  # whether it earns its cost against the app's own Micrometer metrics.
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

resource "aws_ecs_task_definition" "role" {
  for_each                 = local.tasks
  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = each.value.role_arn # null for api and migrate: no AWS permissions

  # x86: CI builds amd64 on GitHub's runners. Graviton is ~20% cheaper but needs a multi-arch
  # (emulated, slow) build; not worth it for a stack that runs for hours.
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name      = each.key
    image     = local.image
    essential = true
    portMappings = each.key == "api" ? [
      { containerPort = 8080, protocol = "tcp" }, # application
      { containerPort = 8081, protocol = "tcp" }, # management: ALB health checks only
    ] : []
    environment = concat(local.common_environment, each.value.extra_env, [
      { name = "SPRING_PROFILES_ACTIVE", value = each.value.profiles },
    ])
    secrets = local.common_secrets
    # SIGTERM -> Spring graceful shutdown (server.shutdown=graceful, 30 s per phase) -> SIGKILL
    # after this. Matches the target group's deregistration delay.
    stopTimeout = 30
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.task[each.key].name
        awslogs-region        = var.region
        awslogs-stream-prefix = each.key
      }
    }
  }])
}

# The migrate step. Runs whenever the migrate task definition changes — that is, on every new
# image — and blocks until the task exits. terraform_data + local-exec because Terraform has no
# "run this task once" resource; the script needs the AWS CLI on the machine running apply.
resource "terraform_data" "migrate" {
  triggers_replace = [aws_ecs_task_definition.role["migrate"].arn]

  provisioner "local-exec" {
    command = "${path.module}/scripts/run-migrate.sh"
    environment = {
      AWS_REGION      = var.region
      CLUSTER         = aws_ecs_cluster.main.name
      TASK_DEFINITION = aws_ecs_task_definition.role["migrate"].arn
      SUBNETS         = join(",", aws_subnet.public[*].id)
      SECURITY_GROUP  = aws_security_group.worker.id
      LOG_GROUP       = aws_cloudwatch_log_group.task["migrate"].name
    }
  }

  # The database, its secret and the network path must exist before the task can succeed.
  depends_on = [
    aws_db_instance.main,
    aws_iam_role_policy.execution,
    aws_route_table_association.public,
    aws_vpc_security_group_ingress_rule.db_from_tasks,
    aws_vpc_security_group_egress_rule.tasks_to_db,
    aws_vpc_security_group_egress_rule.tasks_to_aws_apis,
  ]
}

resource "aws_ecs_service" "api" {
  name             = "api"
  cluster          = aws_ecs_cluster.main.id
  task_definition  = aws_ecs_task_definition.role["api"].arn
  desired_count    = var.api_desired_count
  launch_type      = "FARGATE"
  propagate_tags   = "SERVICE"
  platform_version = "LATEST"

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = true # egress without a NAT gateway; inbound is the SG's job
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  # JVM start on 0.5 vCPU is tens of seconds; don't let the ALB's first failed checks kill
  # tasks that are still booting.
  health_check_grace_period_seconds = 180

  # A rollout that never gets healthy rolls itself back instead of cycling tasks forever.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  # `apply` returns only when the tasks are running and healthy: "apply from zero produces a
  # working deployment" is checked by Terraform, not assumed.
  wait_for_steady_state = true

  depends_on = [terraform_data.migrate, aws_lb_listener.http, aws_iam_role_policy.execution]
}

resource "aws_ecs_service" "worker" {
  name             = "worker"
  cluster          = aws_ecs_cluster.main.id
  task_definition  = aws_ecs_task_definition.role["worker"].arn
  desired_count    = 1
  propagate_tags   = "SERVICE"
  platform_version = "LATEST"

  capacity_provider_strategy {
    capacity_provider = var.worker_use_spot ? "FARGATE_SPOT" : "FARGATE"
    weight            = 1
  }

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.worker.id]
    assign_public_ip = true
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  wait_for_steady_state = true

  depends_on = [terraform_data.migrate, aws_iam_role_policy.worker_task, aws_ecs_cluster_capacity_providers.main]
}
