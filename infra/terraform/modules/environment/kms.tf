# ADR-0023 point 9: customer-managed keys per environment and purpose.

locals {
  key_purposes = toset(["database", "backups", "secrets"])
}

resource "aws_kms_key" "this" {
  for_each = local.key_purposes

  description             = "${local.name} ${each.key}"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "this" {
  for_each = local.key_purposes

  name          = "alias/${local.name}-${each.key}"
  target_key_id = aws_kms_key.this[each.key].key_id
}
