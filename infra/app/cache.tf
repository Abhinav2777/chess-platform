# Valkey 8 — the major the local stack and tests run (valkey/valkey:8-alpine).
# One node, no replica, no snapshots: Valkey is a cache and a transport, never the source of
# truth (ADR-004), so losing it costs presence and pub/sub, not data — and the app degrades
# for exactly that case (ADR-018).

resource "aws_elasticache_subnet_group" "main" {
  name       = var.name
  subnet_ids = aws_subnet.isolated[*].id
}

resource "aws_elasticache_replication_group" "valkey" {
  replication_group_id = var.name
  description          = "chess-platform: matchmaking queue, rate limits, realtime fan-out"
  engine               = "valkey"
  engine_version       = "8.2"
  node_type            = var.cache_node_type
  port                 = 6379

  num_cache_clusters         = 1
  automatic_failover_enabled = false
  multi_az_enabled           = false

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.cache.id]

  at_rest_encryption_enabled = true
  # TLS on the wire. The app switches its client to TLS with SPRING_DATA_REDIS_SSL_ENABLED
  # (7.4); ElastiCache's certificate chains to a public CA the JVM already trusts.
  transit_encryption_enabled = true
  transit_encryption_mode    = "required"

  snapshot_retention_limit   = 0
  apply_immediately          = true
  auto_minor_version_upgrade = true
}
