# Phase 9.4: the load generator, inside the VPC, for one measurement session (ADR-023 §9.4).
# Off by default; `-var loadgen_enabled=true` adds a k6 task definition, its security group and
# log group. Run it with loadtest/aws-loadtest.sh. Nothing here runs on its own.
#
# k6 targets the ALB's PRIVATE address. Calling its public DNS name from inside the VPC hairpins
# through the internet gateway and arrives from the task's public IP, which no security-group
# reference matches. Privately, "ALB admits the loadgen SG" works and the ALB still balances.

variable "loadgen_enabled" {
  description = "Create the k6 load-generator task definition (9.4 measurement sessions only)."
  type        = bool
  default     = false
}

variable "relaxed_auth_rate_limits" {
  description = "Load-test-sized login/register limits on the API (all k6 users share one address). Session-only: production limits are 5 registrations and 10 logins per IP per minute."
  type        = bool
  default     = false
}

locals {
  loadgen = var.loadgen_enabled ? 1 : 0
}

resource "aws_security_group" "loadgen" {
  count       = local.loadgen
  name        = "${var.name}-loadgen"
  description = "k6 load generator: out to the ALB and to the image registry"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.name}-loadgen" }
}

resource "aws_vpc_security_group_egress_rule" "loadgen_to_alb" {
  count                        = local.loadgen
  security_group_id            = aws_security_group.loadgen[0].id
  description                  = "HTTP + WebSocket to the ALB (private address)"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 80
  to_port                      = 80
}

resource "aws_vpc_security_group_egress_rule" "loadgen_to_internet" {
  count             = local.loadgen
  security_group_id = aws_security_group.loadgen[0].id
  description       = "HTTPS: pull grafana/k6 from Docker Hub, write CloudWatch Logs"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_loadgen" {
  count                        = local.loadgen
  security_group_id            = aws_security_group.alb.id
  description                  = "HTTP + WebSocket from the k6 load generator"
  referenced_security_group_id = aws_security_group.loadgen[0].id
  ip_protocol                  = "tcp"
  from_port                    = 80
  to_port                      = 80
}

resource "aws_cloudwatch_log_group" "loadgen" {
  count             = local.loadgen
  name              = "/ecs/${var.name}/loadgen"
  retention_in_days = 7
}

resource "aws_ecs_task_definition" "loadgen" {
  count                    = local.loadgen
  family                   = "${var.name}-loadgen"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  # 1 vCPU / 2 GB: k6 needed 0.74 cores and 445 MiB for 1,000 sockets at stress rates on the
  # laptop. Sized so the generator is never the bottleneck — and its CPU is reported anyway.
  cpu                = 1024
  memory             = 2048
  execution_role_arn = aws_iam_role.execution.arn
  # No task role: k6 calls no AWS API.

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name      = "k6"
    image     = "grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34"
    essential = true
    # The scenario travels inside the task definition (16 KB base64, limit 64 KB): no S3 bucket,
    # no custom image. The summary JSON is printed after the run so it lands in the logs.
    entryPoint = ["sh", "-c"]
    command = [<<-SH
      echo "$SCRIPT_B64" | base64 -d > /tmp/games.js &&
      k6 run --quiet --summary-export /tmp/summary.json /tmp/games.js; status=$?
      echo "K6_SUMMARY_JSON $(tr -d '\n' < /tmp/summary.json)"; echo "K6_EXIT $status"; exit $status
    SH
    ]
    environment = [
      { name = "SCRIPT_B64", value = filebase64("${path.module}/../../loadtest/games.js") },
      # BASE_URL, GAMES, PLAY_SECONDS, RAMP_SECONDS, THINK_* come from run-task overrides.
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.loadgen[0].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "k6"
      }
    }
  }])
}

output "loadgen" {
  description = "For loadtest/aws-loadtest.sh."
  value = var.loadgen_enabled ? {
    cluster         = aws_ecs_cluster.main.name
    task_definition = aws_ecs_task_definition.loadgen[0].arn
    security_group  = aws_security_group.loadgen[0].id
    subnets         = join(",", aws_subnet.public[*].id)
    log_group       = aws_cloudwatch_log_group.loadgen[0].name
    alb_arn_suffix  = aws_lb.main.arn_suffix
    tg_arn_suffix   = aws_lb_target_group.api.arn_suffix
    db_identifier   = aws_db_instance.main.identifier
  } : null
}
