# PostgreSQL 16 — the major every migration and Testcontainers run uses (postgres:16-alpine).
# Single-AZ: Multi-AZ doubles the price for a failover this stack never needs (ROADMAP: SKIP).

resource "aws_db_subnet_group" "main" {
  name       = var.name
  subnet_ids = aws_subnet.isolated[*].id
}

# A parameter group of our own. The default one cannot be modified, so the first tuning
# change would otherwise mean swapping groups — and a reboot — on a live database.
resource "aws_db_parameter_group" "postgres16" {
  name   = "${var.name}-postgres16"
  family = "postgres16"

  # Every statement slower than 250 ms goes to the log (exported to CloudWatch below). The
  # move path's budget is tens of milliseconds; anything this slow is a finding for Phase 9.
  parameter {
    name         = "log_min_duration_statement"
    value        = "250"
    apply_method = "immediate"
  }

  # TLS required for every connection. The default since PG 15 on RDS, written down so it
  # cannot quietly change. pgjdbc's default sslmode=prefer negotiates TLS on its own.
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "immediate"
  }

  # A session left "idle in transaction" holds row locks and blocks vacuum. The application
  # never does this deliberately (short transactions, ADR-005); a bug that did would show up as
  # a closed connection within a minute instead of a slowly growing lock queue.
  parameter {
    name         = "idle_in_transaction_session_timeout"
    value        = "60000"
    apply_method = "immediate"
  }
}

# Created here, before the instance, so Terraform owns it. If RDS creates it on first export,
# it is unmanaged: retention "never expire", and it survives `terraform destroy` — a leftover
# that bills for storage forever.
resource "aws_cloudwatch_log_group" "postgres" {
  name              = "/aws/rds/instance/${var.name}/postgresql"
  retention_in_days = 7
}

resource "aws_db_instance" "main" {
  identifier     = var.name
  engine         = "postgres"
  engine_version = "16" # major only: minor upgrades are automatic and do not show as drift
  instance_class = var.db_instance_class

  db_name  = "chess"
  username = "chess"
  # RDS generates the password and keeps it in Secrets Manager; it is never in Terraform
  # state or in this repository. ECS injects it into the tasks (7.4).
  #
  # Known limitation: RDS rotates this secret every 7 days. Tasks read it at start, so a
  # rotation mid-session would fail new connections until the tasks restart. This stack lives
  # for hours to days (apply -> measure -> destroy), so it is accepted and recorded (ADR-023);
  # a long-lived deployment would use a separate application user, or a driver that re-reads
  # the secret on authentication failure.
  manage_master_user_password = true

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  parameter_group_name   = aws_db_parameter_group.postgres16.name
  publicly_accessible    = false
  multi_az               = false

  enabled_cloudwatch_logs_exports = ["postgresql"]

  # One day of point-in-time recovery; free up to the storage size. Deleted with the instance.
  backup_retention_period  = 1
  delete_automated_backups = true

  # Destroy-by-design: no final snapshot (it would bill after the stack is gone, and the data
  # is test games), and no deletion protection (it would make `terraform destroy` fail).
  # A long-lived environment flips all three.
  skip_final_snapshot = true
  deletion_protection = false
  apply_immediately   = true

  auto_minor_version_upgrade = true

  depends_on = [aws_cloudwatch_log_group.postgres]
}
