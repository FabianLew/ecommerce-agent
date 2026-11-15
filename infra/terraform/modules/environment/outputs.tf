output "load_balancer_dns_name" {
  description = "Point the three host names (CNAME or alias) here."
  value       = aws_lb.this.dns_name
}

output "ecr_repositories" {
  description = "Where CI pushes the images."
  value       = { for key, repo in aws_ecr_repository.this : key => repo.repository_url }
}

output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "migration_task_definition" {
  description = "Run once per rollout before the services are updated (ADR-0023 point 3)."
  value       = aws_ecs_task_definition.this["migrate"].arn
}

output "db_admin_task_definition" {
  value = aws_ecs_task_definition.this["db-admin"].arn
}

output "task_network" {
  description = "Subnets and security group for aws ecs run-task."
  value = {
    subnets         = aws_subnet.public[*].id
    security_groups = [aws_security_group.tasks.id]
  }
}

output "database_endpoint" {
  value = aws_db_instance.this.address
}

output "database_master_secret_arn" {
  value = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "secret_arns" {
  description = "Containers whose values the owner puts with aws secretsmanager put-secret-value."
  value       = { for key, secret in aws_secretsmanager_secret.this : key => secret.arn }
}

output "ses_dkim_tokens" {
  description = "Publish as CNAME <token>._domainkey.<mail_domain> -> <token>.dkim.amazonses.com."
  value       = aws_sesv2_email_identity.notifications.dkim_signing_attributes[0].tokens
}

output "mail_events_topic_arn" {
  value = aws_sns_topic.mail_events.arn
}

output "dump_bucket" {
  value = aws_s3_bucket.dumps.bucket
}
