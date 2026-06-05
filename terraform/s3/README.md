# Phase 16 — product-image S3 bucket (authored, apply deferred)

A **private** S3 bucket for product images. This is **authored, not applied** — the same
posture as the secrets Terraform (ADR-0013): the dev/test path runs entirely on LocalStack,
so the real bucket is only needed when ShopSphere runs on real AWS. The application does not
depend on this existing. See **ADR-0016**.

## What it creates

- A private bucket with **all public access blocked** at every lever.
- `BucketOwnerEnforced` ownership (ACLs disabled).
- SSE-S3 (AES256) encryption at rest.

No bucket policy grants anonymous read. The app reaches objects only through short-lived
**presigned URLs** minted with its own IAM credentials.

## Connecting the app (the dev↔cloud seam)

The app picks its storage backend purely by configuration — the same idea as the RDS
`${DB_HOST}` seam. To point the unchanged binary at this bucket instead of LocalStack:

| Env var        | LocalStack (dev)              | Real S3 (this bucket)                 |
|----------------|-------------------------------|---------------------------------------|
| `S3_ENDPOINT`  | `http://localhost:4566`       | *(unset — SDK resolves real S3)*      |
| `S3_BUCKET`    | `shopsphere-product-images`   | the `bucket_name` output              |
| `AWS_REGION`   | `us-east-1`                   | the `region` output                   |
| credentials    | `S3_ACCESS_KEY/S3_SECRET_KEY` | instance profile / default AWS chain  |

Scope the app's IAM policy to `s3:GetObject` + `s3:PutObject` on the `bucket_arn` and its `/*`.

## When you actually apply (on a lab / own AWS)

```
cp terraform.tfvars.example terraform.tfvars   # set a globally-unique bucket_name
terraform init
terraform plan
terraform apply
# ... use it ...
terraform destroy
```

Do **not** commit `terraform.tfvars`, `.terraform/`, or `*.tfstate`.
