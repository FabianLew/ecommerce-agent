data "aws_region" "current" {}

data "aws_caller_identity" "current" {}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name   = "operator-${var.environment}"
  region = data.aws_region.current.region
  azs    = slice(data.aws_availability_zones.available.names, 0, 2)

  oidc_issuer = "https://${var.auth_host}/realms/${var.oidc_realm}"

  backend_image  = "${aws_ecr_repository.this["backend"].repository_url}:${var.backend_image_tag}"
  frontend_image = "${aws_ecr_repository.this["frontend"].repository_url}:${var.frontend_image_tag}"
}
