# Terraform — Phase 12 (EC2 deploy) + Phase 20 (HTTPS via Caddy)

One `terraform apply` stands up the full cloud deploy in a Whizlabs sandbox:

- an **EC2** instance (Amazon Linux 2023) that installs Docker, writes `compose.cloud.yml` + `Caddyfile`, pulls `poojithvsc/shopsphere:latest` from Docker Hub, and brings up **app + Kafka** (and **Caddy** when `enable_https = true`);
- a **private RDS** Postgres whose only ingress is the EC2's security group.

> **Relationship to `../rds/`:** the Phase-11 `rds/` module stood up a *public* RDS reachable from your laptop — a learning step. This module is the production-posture deploy and **supersedes it**. Do **not** apply both at once (you'd get two RDS instances). For Phase 12+, use this directory.

## Prerequisites

1. A Whizlabs AWS sandbox; credentials exported (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`) or `aws configure` done in the lab.
2. The app image pushed to Docker Hub (Phase 12 does this manually — see the lab runbook `docs/lab-runbook-ec2-https.md`):
   ```
   docker build -t poojithvsc/shopsphere:latest .
   docker login
   docker push poojithvsc/shopsphere:latest
   ```
3. `cp terraform.tfvars.example terraform.tfvars` and fill in `my_ip_cidr`, `db_password`, `jwt_secret`.

## Apply

```
terraform init
terraform apply                 # Phase 12: app over HTTP at :8080
terraform apply -var enable_https=true   # Phase 20: also start Caddy (HTTPS :443, self-signed)
```

Outputs give you `app_http_url`, `app_https_url`, `ec2_public_ip`, and `rds_endpoint`.

## Acceptance checks (run in the lab)

- App: open `http://<ec2_public_ip>:8080/swagger-ui.html`.
- **RDS is private:** `psql -h <rds-endpoint> -U shopsphere` **from your laptop must time out** (only the EC2 SG can reach 5432).
- HTTPS (Phase 20): open `https://<ec2_public_ip>` — the app loads after you accept the self-signed-cert warning.
- Full QA walkthrough end-to-end against the EC2 (see `docs/qa-walkthrough.md`, swapping the base URL).

## Teardown

```
terraform destroy
```

Throwaway-lab posture (no backups, no final snapshot, default VPC, t3.micro) is correct **only** because the lab is ephemeral and holds no real data. See ADR-0011 / ADR-0012. On graduation to your own AWS, the same module runs with a different `terraform.tfvars` and would add: private subnets + NAT, ACM/ALB for TLS (replacing self-signed Caddy — ADR-0020), and SSM for secrets (ADR-0013).
