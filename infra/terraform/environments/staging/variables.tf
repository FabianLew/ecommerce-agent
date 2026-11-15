variable "app_host" {
  type = string
}

variable "api_host" {
  type = string
}

variable "auth_host" {
  type = string
}

variable "certificate_arn" {
  type = string
}

variable "mail_domain" {
  type = string
}

variable "temporal_address" {
  type = string
}

variable "temporal_namespace" {
  type = string
}

variable "backend_image_tag" {
  type = string
}

variable "frontend_image_tag" {
  type = string
}

variable "bedrock_model_arns" {
  type    = list(string)
  default = []
}
