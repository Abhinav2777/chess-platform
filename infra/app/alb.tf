# The only public entry point. HTTP on 80 from the allowlist (ADR-023); WebSockets need no
# special configuration on an ALB — the Upgrade handshake is proxied as-is.

resource "aws_lb" "main" {
  name               = var.name
  load_balancer_type = "application"
  internal           = false
  subnets            = aws_subnet.public[*].id
  security_groups    = [aws_security_group.alb.id]

  # A connection with no frames for this long is closed. The client PINGs every 25 s
  # (GameSocket.ts), so an idle-but-alive game survives; a dead one is reaped within a minute.
  idle_timeout = 60

  # Requests with malformed header names are dropped at the edge instead of reaching Tomcat.
  drop_invalid_header_fields = true

  enable_deletion_protection = false # destroy-by-design
}

resource "aws_lb_target_group" "api" {
  name        = "${var.name}-api"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip" # awsvpc: each task has its own ENI and address

  # How long a draining task keeps its in-flight requests before the ALB drops them. Default is
  # 300 s; the app's graceful shutdown takes at most 30 s, and every deploy and destroy would
  # otherwise wait the full five minutes. Open WebSockets are cut at the end and reconnect
  # (the client's backoff + snapshot recovery, ADR-007).
  deregistration_delay = 30

  health_check {
    port                = "8081" # management port; actuator is not on 8080 in AWS
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}
