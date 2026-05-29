output "rds_endpoint" {
  description = "host:port — connection endpoint for the RDS instance."
  value       = aws_db_instance.this.endpoint
}

output "rds_host" {
  description = "Hostname only (no port). Feed into DB_HOST for `mvn spring-boot:run`."
  value       = aws_db_instance.this.address
}

output "rds_port" {
  description = "Port. Feed into DB_PORT."
  value       = aws_db_instance.this.port
}
