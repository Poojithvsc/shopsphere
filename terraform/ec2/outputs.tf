output "ec2_public_ip" {
  description = "Public IP of the app EC2. The app is at http://<ip>:8080/swagger-ui.html (and https://<ip> when enable_https=true)."
  value       = aws_instance.app.public_ip
}

output "app_http_url" {
  description = "Direct HTTP entry point (Phase 12)."
  value       = "http://${aws_instance.app.public_ip}:8080"
}

output "app_https_url" {
  description = "HTTPS entry point via Caddy (Phase 20; only serves once enable_https=true). Self-signed cert — expect a browser warning."
  value       = "https://${aws_instance.app.public_ip}"
}

output "rds_endpoint" {
  description = "host:port of the PRIVATE RDS. Reachable from the EC2 only — a psql from your laptop should time out (that is the Phase-12 acceptance check). Empty when use_rds=false (Postgres runs as a container on the EC2)."
  value       = var.use_rds ? aws_db_instance.this[0].endpoint : "(none — Postgres is a container on the EC2; use_rds=false)"
}

output "rds_host" {
  description = "RDS hostname only — what the EC2's compose.cloud.yml uses as DB_HOST."
  value       = var.use_rds ? aws_db_instance.this[0].address : "postgres (in-EC2 container)"
}
