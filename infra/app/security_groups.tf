# Who may talk to whom — by security group reference, not by address, so the rules keep
# holding as tasks come and go with new IPs.
#
#   allowlist --80--> alb --8080/8081--> api --5432--> db
#                                   api/worker --6379--> cache
#                                   api/worker --443--> AWS APIs, ECR (via the internet gateway)
#
# One rule per resource (aws_vpc_security_group_*_rule), not inline blocks: inline and
# standalone rules on the same group fight each other, and per-rule resources show exactly
# which rule a plan changes.

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "ALB: HTTP from the allowlist only (ADR-023)"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.name}-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each          = toset(var.allowed_ingress_cidrs)
  security_group_id = aws_security_group.alb.id
  description       = "HTTP + WebSocket from an allowlisted address"
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "alb_to_api" {
  security_group_id            = aws_security_group.alb.id
  description                  = "To API tasks (traffic and health checks)"
  referenced_security_group_id = aws_security_group.api.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# The ALB health-checks the management port (actuator moved there in the aws profile, 7.4).
resource "aws_vpc_security_group_egress_rule" "alb_to_api_management" {
  security_group_id            = aws_security_group.alb.id
  description                  = "To API tasks: health checks on the management port"
  referenced_security_group_id = aws_security_group.api.id
  ip_protocol                  = "tcp"
  from_port                    = 8081
  to_port                      = 8081
}

resource "aws_security_group" "api" {
  name        = "${var.name}-api"
  description = "API tasks: reachable from the ALB only"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.name}-api" }
}

resource "aws_vpc_security_group_ingress_rule" "api_from_alb" {
  security_group_id            = aws_security_group.api.id
  description                  = "From the ALB only - the tasks have public IPs, so this rule is what keeps them unreachable directly"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_vpc_security_group_ingress_rule" "api_management_from_alb" {
  security_group_id            = aws_security_group.api.id
  description                  = "Health checks from the ALB on the management port"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8081
  to_port                      = 8081
}

# Worker and the one-off migrate task: nothing calls them.
resource "aws_security_group" "worker" {
  name        = "${var.name}-worker"
  description = "Worker and migrate tasks: no inbound at all"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.name}-worker" }
}

resource "aws_security_group" "db" {
  name        = "${var.name}-db"
  description = "PostgreSQL: from API and worker tasks only"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.name}-db" }
}

resource "aws_security_group" "cache" {
  name        = "${var.name}-cache"
  description = "Valkey: from API and worker tasks only"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.name}-cache" }
}

locals {
  task_security_groups = {
    api    = aws_security_group.api.id
    worker = aws_security_group.worker.id
  }
}

resource "aws_vpc_security_group_ingress_rule" "db_from_tasks" {
  for_each                     = local.task_security_groups
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL from ${each.key} tasks"
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_ingress_rule" "cache_from_tasks" {
  for_each                     = local.task_security_groups
  security_group_id            = aws_security_group.cache.id
  description                  = "Valkey from ${each.key} tasks"
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
}

resource "aws_vpc_security_group_egress_rule" "tasks_to_db" {
  for_each                     = local.task_security_groups
  security_group_id            = each.value
  description                  = "To PostgreSQL"
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_vpc_security_group_egress_rule" "tasks_to_cache" {
  for_each                     = local.task_security_groups
  security_group_id            = each.value
  description                  = "To Valkey"
  referenced_security_group_id = aws_security_group.cache.id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
}

# HTTPS to anywhere: ECR, S3 (image layers), SQS, Secrets Manager, CloudWatch Logs, STS. Their
# addresses are not stable enough to list; without a NAT gateway or VPC endpoints this path
# goes out through the internet gateway. Interface endpoints would allow narrowing this, at
# ~$7/month each per AZ — five services x 2 AZs is more than the NAT gateway we avoided.
resource "aws_vpc_security_group_egress_rule" "tasks_to_aws_apis" {
  for_each          = local.task_security_groups
  security_group_id = each.value
  description       = "HTTPS to AWS APIs and ECR"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
