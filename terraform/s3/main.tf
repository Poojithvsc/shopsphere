# ShopSphere — Phase 16: private S3 bucket for product images.
#
# AUTHORED, NOT APPLIED. Like the secrets work (ADR-0013), this is deferred: the
# dev/test path runs entirely on LocalStack (Testcontainers + docker-compose), so the
# real bucket is only needed when ShopSphere runs on real AWS. `terraform apply` here is
# a future step on a lab/own-AWS — the app does not depend on it existing. See ADR-0016.
#
# Posture is the opposite of the throwaway RDS box: this bucket is PRIVATE. Public access
# is blocked at every lever; the app reaches objects only through short-lived presigned
# URLs minted with its own IAM credentials. No bucket policy grants anonymous read.

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

resource "aws_s3_bucket" "product_images" {
  bucket = var.bucket_name
}

# Deny every form of public access. Presigned URLs still work — they authenticate as the
# app's IAM principal, not as the public — so this does not affect the read path.
resource "aws_s3_bucket_public_access_block" "product_images" {
  bucket                  = aws_s3_bucket.product_images.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Bucket-owner-enforced: ACLs off, ownership is unambiguous. Modern S3 default.
resource "aws_s3_bucket_ownership_controls" "product_images" {
  bucket = aws_s3_bucket.product_images.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Encrypt at rest with SSE-S3 (AES256). No KMS key to manage (XP YAGNI) — upgrade to
# aws:kms only if a compliance requirement appears.
resource "aws_s3_bucket_server_side_encryption_configuration" "product_images" {
  bucket = aws_s3_bucket.product_images.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}
