variable "aws_region" {
  description = "AWS region for the bucket. Match the app's AWS_REGION."
  type        = string
  default     = "us-east-1"
}

variable "bucket_name" {
  description = "Product-image bucket name. S3 bucket names are GLOBALLY unique, so the default will likely collide — override with a unique suffix (e.g. shopsphere-product-images-<account-id>). Feeds the app's S3_BUCKET."
  type        = string
  default     = "shopsphere-product-images"
}
