# ADR-0023 point 9: PostgreSQL 17, KMS-encrypted, PITR, deletion protection, private. The master password is
# managed by RDS in Secrets Manager; the application roles of ADR-0022 are created from it once (README).

resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_parameter_group" "this" {
  name   = local.name
  family = "postgres17"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }
}

resource "aws_db_instance" "this" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = "17"
  instance_class = var.db_instance_class
  db_name        = "operator"

  username                      = "operator_admin"
  manage_master_user_password   = true
  master_user_secret_kms_key_id = aws_kms_key.this["secrets"].arn

  allocated_storage     = var.db_allocated_storage_gb
  max_allocated_storage = var.db_max_allocated_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.this["database"].arn

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.database.id]
  parameter_group_name   = aws_db_parameter_group.this.name
  publicly_accessible    = false
  multi_az               = var.db_multi_az

  backup_retention_period  = var.backup_retention_days
  backup_window            = "02:00-03:00"
  maintenance_window       = "sun:03:30-sun:04:30"
  copy_tags_to_snapshot    = true
  delete_automated_backups = true

  auto_minor_version_upgrade = true
  deletion_protection        = true
  skip_final_snapshot        = false
  final_snapshot_identifier  = "${local.name}-final"
}
