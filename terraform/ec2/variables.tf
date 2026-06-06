variable "aws_region" {
  description = "AWS region for the Whizlabs sandbox. Override if your lab is not us-east-1."
  type        = string
  default     = "us-east-1"
}

variable "name_prefix" {
  description = "Prefix for resource names/identifiers."
  type        = string
  default     = "shopsphere"
}

# --- EC2 ---

variable "instance_class" {
  description = "EC2 instance type. t3.micro (1 GiB) per the plan, but app + Kafka are two JVMs — if the app OOMs on boot, bump to t3.small (2 GiB). One-line change, no other edits."
  type        = string
  default     = "t3.micro"
}

variable "app_image" {
  description = "Docker Hub image the EC2 pulls. Built and pushed manually in Phase 12 (Phase 19 would automate)."
  type        = string
  default     = "poojithvsc/shopsphere:latest"
}

variable "key_name" {
  description = "Optional EC2 key pair name for SSH debugging. Leave blank to launch without SSH access (user-data still runs)."
  type        = string
  default     = ""
}

variable "use_rds" {
  description = "When true (the real Phase-12 design), provision a PRIVATE managed RDS and point the app at it. When false, skip all RDS resources and run Postgres as a container on the EC2 instead — a fallback for Whizlabs sandboxes that deny RDS (the SAA Cloud Sandbox denies even rds:Describe). The container path does NOT demonstrate the 'private managed DB / network-as-security-control' lesson of ADR-0012; that acceptance criterion is deferred to the guided RDS lab or own-AWS."
  type        = bool
  default     = true
}

variable "create_instance_profile" {
  description = "Create an IAM role + instance profile for the EC2. Whizlabs IAM users often lack iam:CreateRole; set false to skip it. The lab QA flow needs no AWS API access from the box (public image pull, password DB auth, S3 dormant), so skipping is safe. Re-enable on own-AWS when SSM/Parameter Store land (ADR-0013)."
  type        = bool
  default     = true
}

variable "enable_https" {
  description = "Phase 20 toggle: when true, user-data also starts the Caddy container (HTTPS via tls internal on :443). Leave false for a Phase-12-only deploy."
  type        = bool
  default     = false
}

variable "jwt_secret" {
  description = "JWT signing secret for the deployed app (>= 32 bytes). Set in terraform.tfvars (gitignored); never committed. Generate: openssl rand -base64 48"
  type        = string
  sensitive   = true
}

# --- RDS (now private) ---

variable "engine_version" {
  description = "Postgres engine version. Major-only ('16') matches local docker-compose postgres:16."
  type        = string
  default     = "16"
}

variable "db_instance_class" {
  description = "RDS instance class. db.t4g.micro is the cheapest Graviton burstable — plenty for a lab."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_name" {
  description = "Initial database name. Mirrors the local dev DB so Flyway runs identically."
  type        = string
  default     = "shopsphere"
}

variable "db_username" {
  description = "Master username. Mirrors local dev."
  type        = string
  default     = "shopsphere"
}

variable "db_password" {
  description = "RDS master password. Set in terraform.tfvars (gitignored); never committed."
  type        = string
  sensitive   = true
}

variable "my_ip_cidr" {
  description = "The developer's public IP as a /32 — the only source allowed to reach SSH (22). Find it with: curl -s https://checkip.amazonaws.com . NOTE: this is NO LONGER allowed to reach RDS (5432) — that is the Phase-12 privacy flip."
  type        = string
}
