variable "environment" {
  description = "staging or production; part of every resource name."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be staging or production."
  }
}

variable "vpc_cidr" {
  description = "Address range of the environment's VPC."
  type        = string
}

variable "app_host" {
  description = "Host name of the web application, for example app.example.com."
  type        = string
}

variable "api_host" {
  description = "Host name of the backend API and the webhooks."
  type        = string
}

variable "auth_host" {
  description = "Host name of Keycloak."
  type        = string
}

variable "certificate_arn" {
  description = "ACM certificate in eu-central-1 covering the three host names, requested and validated by the owner."
  type        = string
}

variable "mail_domain" {
  description = "Domain that SES sends notifications from; also the domain of the Message-ID (ADR-0021)."
  type        = string
}

variable "temporal_address" {
  description = "Temporal Cloud endpoint of the environment's namespace."
  type        = string
}

variable "temporal_namespace" {
  description = "Temporal Cloud namespace, for example operator-staging.<account>."
  type        = string
}

variable "oidc_realm" {
  description = "Keycloak realm of the application."
  type        = string
  default     = "operator"
}

variable "backend_image_tag" {
  description = "Tag of the backend image in ECR (one image for api, worker and the migration task)."
  type        = string
}

variable "frontend_image_tag" {
  description = "Tag of the frontend image in ECR."
  type        = string
}

variable "keycloak_image" {
  description = "Keycloak image, pinned to a version."
  type        = string
  default     = "quay.io/keycloak/keycloak:26.4"
}

variable "adot_image" {
  description = "AWS Distro for OpenTelemetry collector image, pinned to a version."
  type        = string
  default     = "public.ecr.aws/aws-observability/aws-otel-collector:v0.43.3"
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
}

variable "db_allocated_storage_gb" {
  description = "Initial gp3 storage of the database."
  type        = number
  default     = 20
}

variable "db_max_allocated_storage_gb" {
  description = "Upper limit of storage autoscaling."
  type        = number
  default     = 100
}

variable "db_multi_az" {
  description = "Multi-AZ standby (a cost decision of the owner, ADR-0023 point 9)."
  type        = bool
  default     = false
}

variable "use_fargate_spot" {
  description = "Run the services on Fargate Spot (staging only)."
  type        = bool
  default     = false
}

variable "services" {
  description = "Size and count of each long-running service."
  type = map(object({
    cpu           = number
    memory        = number
    desired_count = number
  }))
  default = {
    api      = { cpu = 512, memory = 1024, desired_count = 1 }
    worker   = { cpu = 512, memory = 1024, desired_count = 1 }
    frontend = { cpu = 256, memory = 512, desired_count = 1 }
    keycloak = { cpu = 512, memory = 1024, desired_count = 1 }
  }
}

variable "log_retention_days" {
  description = "Retention of the container logs."
  type        = number
  default     = 30
}

variable "backup_retention_days" {
  description = "Point-in-time recovery window of RDS (ADR-0022 point 7)."
  type        = number
  default     = 14
}

variable "dump_retention_days" {
  description = "Lifetime of the logical dumps in S3 (ADR-0022 point 7)."
  type        = number
  default     = 30
}

variable "bedrock_model_arns" {
  description = "Bedrock inference profiles and foundation models in the EU that the worker may invoke; empty means none."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Tags added to every resource."
  type        = map(string)
  default     = {}
}

variable "direct_anthropic_api_enabled" {
  description = "Give the backend the direct Anthropic API key (synthetic data only); never in production."
  type        = bool
  default     = false

  validation {
    condition     = !(var.environment == "production" && var.direct_anthropic_api_enabled)
    error_message = "Production uses only Bedrock with EU processing (ADR-0023 point 6)."
  }
}
