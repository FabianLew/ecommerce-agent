# Root of the production environment (ADR-0023 point 9). Plan only until the owner approves the plan and the estimate.

terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Partial configuration: bucket, key and region come from backend.hcl (README); S3-native state locking.
  backend "s3" {}
}

provider "aws" {
  region = "eu-central-1"

  default_tags {
    tags = {
      project     = "ecommerce-agent"
      environment = "production"
      managed-by  = "terraform"
    }
  }
}

module "environment" {
  source = "../../modules/environment"

  environment = "production"
  vpc_cidr    = "10.30.0.0/16"

  app_host        = var.app_host
  api_host        = var.api_host
  auth_host       = var.auth_host
  certificate_arn = var.certificate_arn
  mail_domain     = var.mail_domain

  temporal_address   = var.temporal_address
  temporal_namespace = var.temporal_namespace

  backend_image_tag  = var.backend_image_tag
  frontend_image_tag = var.frontend_image_tag

  db_instance_class = "db.t4g.small"
  db_multi_az       = false
  use_fargate_spot  = false

  direct_anthropic_api_enabled = false
  bedrock_model_arns           = var.bedrock_model_arns
}
