output "bucket_name" {
  description = "Bucket name — set as the app's S3_BUCKET."
  value       = aws_s3_bucket.product_images.id
}

output "bucket_arn" {
  description = "Bucket ARN — scope the app's IAM policy (s3:GetObject/PutObject) to this and its /*."
  value       = aws_s3_bucket.product_images.arn
}

output "region" {
  description = "Region — set as the app's AWS_REGION."
  value       = var.aws_region
}
