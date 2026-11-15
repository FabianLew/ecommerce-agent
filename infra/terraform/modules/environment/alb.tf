# The only entry point (ADR-0023 point 9): HTTPS with the owner's ACM certificate, routed by host name.

resource "aws_lb" "this" {
  name                       = local.name
  load_balancer_type         = "application"
  internal                   = false
  security_groups            = [aws_security_group.alb.id]
  subnets                    = aws_subnet.public[*].id
  drop_invalid_header_fields = true
  enable_deletion_protection = true
}

locals {
  routed = {
    api = {
      host        = var.api_host
      port        = 8080
      health_path = "/actuator/health"
      health_port = "traffic-port"
      priority    = 10
    }
    frontend = {
      host        = var.app_host
      port        = 3000
      health_path = "/"
      health_port = "traffic-port"
      priority    = 20
    }
    keycloak = {
      host        = var.auth_host
      port        = 8080
      health_path = "/health/ready"
      health_port = "9000"
      priority    = 30
    }
  }
}

resource "aws_lb_target_group" "this" {
  for_each = local.routed

  name                 = "${local.name}-${each.key}"
  port                 = each.value.port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.this.id
  deregistration_delay = 30

  health_check {
    path                = each.value.health_path
    port                = each.value.health_port
    matcher             = "200-399"
    interval            = 15
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener_rule" "this" {
  for_each = local.routed

  listener_arn = aws_lb_listener.https.arn
  priority     = each.value.priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[each.key].arn
  }

  condition {
    host_header {
      values = [each.value.host]
    }
  }
}
