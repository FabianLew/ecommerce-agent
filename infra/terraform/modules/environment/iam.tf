# One execution role per task family, so each family can read only its own secrets (the migration secret only
# reaches the migration task, ADR-0023 point 4), and one task role per family with what its code calls.

locals {
  family_secrets = {
    api = concat(
      [
        aws_secretsmanager_secret.this["db-runtime"].arn,
        aws_secretsmanager_secret.this["temporal-api-key"].arn,
        aws_secretsmanager_secret.this["integration-secrets-key"].arn,
      ],
      var.direct_anthropic_api_enabled ? [aws_secretsmanager_secret.this["anthropic-api-key"].arn] : [],
    )
    worker = concat(
      [
        aws_secretsmanager_secret.this["db-runtime"].arn,
        aws_secretsmanager_secret.this["temporal-api-key"].arn,
        aws_secretsmanager_secret.this["integration-secrets-key"].arn,
      ],
      var.direct_anthropic_api_enabled ? [aws_secretsmanager_secret.this["anthropic-api-key"].arn] : [],
    )
    frontend = [aws_secretsmanager_secret.this["frontend"].arn]
    keycloak = [
      aws_secretsmanager_secret.this["keycloak-db"].arn,
      aws_secretsmanager_secret.this["keycloak-admin"].arn,
    ]
    migrate  = [aws_secretsmanager_secret.this["db-migrator"].arn]
    db-admin = [aws_db_instance.this.master_user_secret[0].secret_arn]
  }
}

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "execution" {
  for_each = local.family_secrets

  name               = "${local.name}-${each.key}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  for_each = aws_iam_role.execution

  role       = each.value.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  for_each = local.family_secrets

  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = each.value
  }

  statement {
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.this["secrets"].arn]
  }

  statement {
    actions   = ["kms:Decrypt"]
    resources = [aws_kms_key.this["backups"].arn]
    sid       = "PullImagesFromKmsEncryptedEcr"
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  for_each = local.family_secrets

  name   = "secrets"
  role   = aws_iam_role.execution[each.key].id
  policy = data.aws_iam_policy_document.execution_secrets[each.key].json
}

resource "aws_iam_role" "task" {
  for_each = local.family_secrets

  name               = "${local.name}-${each.key}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

# The OpenTelemetry collector sidecar of the backend tasks sends metrics and traces.
data "aws_iam_policy_document" "telemetry" {
  statement {
    actions = [
      "cloudwatch:PutMetricData",
      "logs:CreateLogStream",
      "logs:PutLogEvents",
      "logs:DescribeLogStreams",
      "logs:CreateLogGroup",
      "xray:PutTraceSegments",
      "xray:PutTelemetryRecords",
      "xray:GetSamplingRules",
      "xray:GetSamplingTargets",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "telemetry" {
  for_each = toset(["api", "worker"])

  name   = "telemetry"
  role   = aws_iam_role.task[each.key].id
  policy = data.aws_iam_policy_document.telemetry.json
}

# Notifications are sent by the worker through SES with the environment's configuration set.
data "aws_iam_policy_document" "worker" {
  statement {
    actions = ["ses:SendEmail", "ses:SendRawEmail"]
    resources = [
      aws_sesv2_email_identity.notifications.arn,
      aws_sesv2_configuration_set.notifications.arn,
    ]
  }

  dynamic "statement" {
    for_each = length(var.bedrock_model_arns) > 0 ? [1] : []

    content {
      actions   = ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"]
      resources = var.bedrock_model_arns
    }
  }
}

resource "aws_iam_role_policy" "worker" {
  name   = "worker"
  role   = aws_iam_role.task["worker"].id
  policy = data.aws_iam_policy_document.worker.json
}

# The administrative task writes the logical dumps of ADR-0022 point 7.
data "aws_iam_policy_document" "db_admin" {
  statement {
    actions   = ["s3:PutObject", "s3:GetObject", "s3:ListBucket"]
    resources = [aws_s3_bucket.dumps.arn, "${aws_s3_bucket.dumps.arn}/*"]
  }

  statement {
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [aws_kms_key.this["backups"].arn]
  }
}

resource "aws_iam_role_policy" "db_admin" {
  name   = "dumps"
  role   = aws_iam_role.task["db-admin"].id
  policy = data.aws_iam_policy_document.db_admin.json
}
