# ShopSphere — Phase 11: RDS Postgres in a Whizlabs sandbox.
#
# Throwaway posture: a publicly-reachable Postgres locked to the developer's /32,
# no backups, no final snapshot. This is correct ONLY because the lab is ephemeral
# (4h) and holds no real data. Phase 12 flips publicly_accessible to false and moves
# ingress behind the EC2 security group. See ADR-0011.

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

# Whizlabs sandboxes ship a default VPC with subnets across AZs. Reuse it rather
# than provisioning a network — the lab is throwaway and a 4h DB does not warrant
# its own VPC (XP YAGNI).
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name_prefix}-rds"
  subnet_ids = data.aws_subnets.default.ids
}

resource "aws_security_group" "rds" {
  name        = "${var.name_prefix}-rds"
  description = "ShopSphere RDS — Postgres 5432 from the developer laptop only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "Postgres from the developer's public /32"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.my_ip_cidr]
  }

  egress {
    description = "Unrestricted egress"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name_prefix}-postgres"
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage = 20
  storage_type      = "gp3"

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password
  port     = 5432

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = true

  # Throwaway-lab posture — see ADR-0011.
  skip_final_snapshot     = true
  backup_retention_period = 0
  apply_immediately       = true
  deletion_protection     = false
}
