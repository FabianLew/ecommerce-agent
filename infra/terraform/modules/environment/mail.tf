# ADR-0023 point 9 and ADR-0021: SES in eu-central-1 for notifications. Delivery, bounce and complaint events go
# to an SNS topic; the subscription to the backend's event endpoint is added with the SES adapter.

resource "aws_sesv2_email_identity" "notifications" {
  email_identity         = var.mail_domain
  configuration_set_name = aws_sesv2_configuration_set.notifications.configuration_set_name

  dkim_signing_attributes {
    next_signing_key_length = "RSA_2048_BIT"
  }
}

resource "aws_sesv2_configuration_set" "notifications" {
  configuration_set_name = "${local.name}-notifications"

  delivery_options {
    tls_policy = "REQUIRE"
  }

  reputation_options {
    reputation_metrics_enabled = true
  }

  suppression_options {
    suppressed_reasons = ["BOUNCE", "COMPLAINT"]
  }
}

# Not encrypted with the AWS-managed SNS key: SES cannot publish to a topic under that key.
resource "aws_sns_topic" "mail_events" {
  name = "${local.name}-mail-events"
}

data "aws_iam_policy_document" "mail_events" {
  statement {
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.mail_events.arn]

    principals {
      type        = "Service"
      identifiers = ["ses.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "mail_events" {
  arn    = aws_sns_topic.mail_events.arn
  policy = data.aws_iam_policy_document.mail_events.json
}

resource "aws_sesv2_configuration_set_event_destination" "mail_events" {
  configuration_set_name = aws_sesv2_configuration_set.notifications.configuration_set_name
  event_destination_name = "delivery-events"

  event_destination {
    enabled              = true
    matching_event_types = ["SEND", "DELIVERY", "BOUNCE", "COMPLAINT", "REJECT", "DELIVERY_DELAY"]

    sns_destination {
      topic_arn = aws_sns_topic.mail_events.arn
    }
  }

  depends_on = [aws_sns_topic_policy.mail_events]
}
