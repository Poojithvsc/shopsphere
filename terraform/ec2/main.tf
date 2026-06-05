# ShopSphere — Phase 12: EC2 deploy + self-hosted Kafka; RDS goes private.
#
# This is the Phase-11 RDS module's grown-up sibling. Phase 11 stood up a *public* RDS reachable
# from the developer laptop (a deliberate learning step). Phase 12 is the production-posture deploy:
# one `terraform apply` provisions BOTH an EC2 instance running the app + Kafka AND a PRIVATE RDS whose
# only ingress is the EC2's security group. The app talks to RDS over the VPC; the laptop cannot.
#
# Throwaway-lab posture (see ADR-0011/0012): default VPC, no backups, no final snapshot, t3.micro.
# Correct ONLY because the Whizlabs lab is ephemeral (4h) and holds no real data.
#
# Supersedes terraform/rds/ for the deploy scenario — do not apply both at once (they would create
# two RDS instances). See terraform/ec2/README.md.

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

# Reuse the lab's default VPC/subnets rather than provisioning a network (XP YAGNI; the lab is
# throwaway). Same choice as the Phase-11 rds module.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Latest Amazon Linux 2023 AMI — owned by Amazon, resolved at apply time so the runbook never pins a
# stale AMI id that rots between lab sessions.
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }
  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
}

# ---------------------------------------------------------------------------
# Security groups
# ---------------------------------------------------------------------------

# EC2: HTTP (8080) and HTTPS (443) open to the world for the QA walkthrough; SSH (22) only from the
# developer /32. 443 is here from the start so Phase 20 (Caddy) needs no SG change — it just turns on.
resource "aws_security_group" "ec2" {
  name        = "${var.name_prefix}-ec2"
  description = "ShopSphere EC2 - app 8080, HTTPS 443 from anywhere; SSH from developer /32"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "App HTTP (direct, Phase 12)"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS via Caddy (Phase 20)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "SSH from the developer public /32"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.my_ip_cidr]
  }

  egress {
    description = "Unrestricted egress (Docker Hub pull, RDS, OS updates)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# RDS: PRIVATE. The posture flip from Phase 11 — ingress is ONLY the EC2 security group, never a
# laptop /32. Proving this is an acceptance criterion: `psql -h <rds-endpoint>` from the laptop must
# time out. See ADR-0012.
resource "aws_security_group" "rds" {
  name        = "${var.name_prefix}-rds"
  description = "ShopSphere RDS - Postgres 5432 from the EC2 security group only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "Postgres from the EC2 instances only"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ec2.id]
  }

  egress {
    description = "Unrestricted egress"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# ---------------------------------------------------------------------------
# RDS (private)
# ---------------------------------------------------------------------------

resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-rds"
  subnet_ids = data.aws_subnets.default.ids
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name_prefix}-postgres"
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.db_instance_class

  allocated_storage = 20
  storage_type      = "gp3"

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password
  port     = 5432

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false # the Phase-12 flip — see ADR-0012

  # Throwaway-lab posture — see ADR-0011/0012.
  skip_final_snapshot     = true
  backup_retention_period = 0
  apply_immediately       = true
  deletion_protection     = false
}

# ---------------------------------------------------------------------------
# IAM instance profile (minimal — no SSM yet; Phase 13 documents what own-AWS would add)
# ---------------------------------------------------------------------------

resource "aws_iam_role" "ec2" {
  name = "${var.name_prefix}-ec2"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${var.name_prefix}-ec2"
  role = aws_iam_role.ec2.name
}

# ---------------------------------------------------------------------------
# EC2 instance — user-data installs Docker, writes compose.cloud.yml, brings up the stack
# ---------------------------------------------------------------------------

resource "aws_instance" "app" {
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_class
  subnet_id              = data.aws_subnets.default.ids[0]
  vpc_security_group_ids = [aws_security_group.ec2.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name
  key_name               = var.key_name != "" ? var.key_name : null

  # The compose file and Caddyfile are baked into user-data from the repo copies so the running EC2
  # matches what is committed (no drift between the lab box and version control).
  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    image         = var.app_image
    db_host       = aws_db_instance.this.address
    db_port       = "5432"
    db_name       = var.db_name
    db_user       = var.db_username
    db_password   = var.db_password
    jwt_secret    = var.jwt_secret
    aws_region    = var.aws_region
    enable_https  = var.enable_https
    compose_cloud = file("${path.module}/../../compose.cloud.yml")
    caddyfile     = file("${path.module}/../../Caddyfile")
  })

  # Re-run user-data if any of its inputs change.
  user_data_replace_on_change = true

  tags = {
    Name = "${var.name_prefix}-app"
  }

  depends_on = [aws_db_instance.this]
}
