# ADR-0023 point 4: every secret lives in Secrets Manager. Terraform creates the containers only; the values are
# put by the owner (README), so they never enter the Terraform state.

locals {
  secrets = {
    db-runtime              = "operator_runtime: JSON {username, password}"
    db-migrator             = "operator_migrator: JSON {username, password}; only for the migration task"
    keycloak-db             = "Keycloak database role: JSON {username, password}"
    keycloak-admin          = "Keycloak bootstrap administrator: JSON {username, password}"
    temporal-api-key        = "Temporal Cloud API key of the environment's namespace"
    integration-secrets-key = "Envelope key of the integration credentials: JSON {key, key_id}"
    frontend                = "Frontend session: JSON {oidc_client_secret, session_secret}"
    anthropic-api-key       = "Direct Anthropic API key; synthetic data only, never set in production (ADR-0023 point 6)"
  }
}

resource "aws_secretsmanager_secret" "this" {
  for_each = local.secrets

  name        = "${local.name}/${each.key}"
  description = each.value
  kms_key_id  = aws_kms_key.this["secrets"].arn
}
