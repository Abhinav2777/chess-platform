# Remote state for the app stack (infra/app) and, after the first apply, for this stack too.
#
# State files hold resource IDs and, for some resources, secrets in plain text (an RDS
# password set in Terraform would be one — which is why infra/app lets RDS manage its own). So:
# private, encrypted, TLS-only, versioned.

locals {
  state_bucket = "chess-platform-tfstate-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket" "state" {
  # Bucket names are global across all AWS accounts; the account ID makes this one unique and
  # predictable, so infra/app can name it without a lookup.
  bucket = local.state_bucket

  # `terraform destroy` here would delete the record of what exists — after which nothing
  # knows what to destroy. Refused at plan time; removing this line is a deliberate act.
  lifecycle {
    prevent_destroy = true
  }
}

# Every write keeps the previous version: a bad apply or a corrupted state is recoverable.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    # SSE-S3, not KMS: a customer-managed key costs $1/month and adds nothing a single-owner
    # account needs; the access control that matters is the bucket policy and IAM.
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ACLs off: access is decided by policy alone, and every object belongs to this account.
resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Old versions are for recovery, not for ever. Lock files (use_lockfile) are small objects
# written and deleted on every run; their versions expire the same way.
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.state]
}

data "aws_iam_policy_document" "state_tls_only" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.state.arn,
      "${aws_s3_bucket.state.arn}/*",
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

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.state_tls_only.json

  # A bucket policy is refused while Block Public Access is still being applied.
  depends_on = [aws_s3_bucket_public_access_block.state]
}
