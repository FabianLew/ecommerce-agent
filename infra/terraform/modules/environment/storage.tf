# ADR-0022 point 7: logical dumps, SSE-KMS, private, versioned, deleted after 30 days; a deleted object version
# disappears a day later, so a deleted row is gone from every copy within the dump lifetime.

resource "aws_s3_bucket" "dumps" {
  bucket = "${local.name}-dumps-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_ownership_controls" "dumps" {
  bucket = aws_s3_bucket.dumps.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "dumps" {
  bucket = aws_s3_bucket.dumps.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "dumps" {
  bucket = aws_s3_bucket.dumps.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "dumps" {
  bucket = aws_s3_bucket.dumps.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.this["backups"].arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "dumps" {
  bucket = aws_s3_bucket.dumps.id

  rule {
    id     = "expire-dumps"
    status = "Enabled"

    filter {}

    expiration {
      days = var.dump_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 1
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.dumps]
}

data "aws_iam_policy_document" "dumps_tls_only" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.dumps.arn,
      "${aws_s3_bucket.dumps.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "dumps" {
  bucket = aws_s3_bucket.dumps.id
  policy = data.aws_iam_policy_document.dumps_tls_only.json

  depends_on = [aws_s3_bucket_public_access_block.dumps]
}

resource "aws_ecr_repository" "this" {
  for_each = toset(["backend", "frontend"])

  name                 = "${local.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = aws_kms_key.this["backups"].arn
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each   = aws_ecr_repository.this
  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 30 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 30
      }
      action = { type = "expire" }
    }]
  })
}
