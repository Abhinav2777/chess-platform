output "vpc_id" {
  value = aws_vpc.main.id
}

output "db_endpoint" {
  description = "host:port, private — resolvable and reachable only inside the VPC."
  value       = aws_db_instance.main.endpoint
}

output "db_master_secret_arn" {
  description = "RDS-managed secret: JSON with username and password."
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}

output "cache_endpoint" {
  value = aws_elasticache_replication_group.valkey.primary_endpoint_address
}

output "game_events_queue_url" {
  value = aws_sqs_queue.game_events.url
}

output "app_url" {
  description = "Open this — from an address in allowed_ingress_cidrs."
  value       = "http://${aws_lb.main.dns_name}"
}
