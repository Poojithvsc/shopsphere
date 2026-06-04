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

variable "engine_version" {
  description = "Postgres engine version. Major-only ('16') lets RDS pick the latest supported minor, matching the local docker-compose postgres:16 — no schema divergence."
  type        = string
  default     = "16"
}

variable "instance_class" {
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
  description = "The developer's public IP as a /32 — the only source allowed to reach 5432. Find it with: curl -s https://checkip.amazonaws.com"
  type        = string
}
