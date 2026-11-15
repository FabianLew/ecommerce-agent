# infra/terraform: staging and production on AWS (ADR-0023 point 9)

Nothing here has been applied. `plan` and `apply` wait for the owner's approval of the plan and of
[the cost estimate](../../docs/operations/aws-cost-estimate.md). CI only runs `fmt -check` and `validate`
(`-backend=false`, no AWS credentials).

## Layout

| Path | What |
| --- | --- |
| `modules/environment` | One environment: VPC, load balancer, ECS Fargate cluster and services, RDS PostgreSQL 17, KMS keys, Secrets Manager containers, S3 dump bucket, ECR, SES with events to SNS, log groups |
| `environments/staging` | Staging root: `db.t4g.micro`, Fargate Spot, direct Anthropic key allowed (synthetic data only) |
| `environments/production` | Production root: `db.t4g.small`, Fargate, Bedrock only (the module refuses the direct key) |

Region `eu-central-1` (Frankfurt). Two availability zones. The tasks run in public subnets with a public IP and accept
inbound traffic only from the load balancer; the database is in private subnets without a route to the internet.

## What the owner prepares once (not in Terraform)

1. An AWS account (or two: staging and production), with IAM Identity Center users; no root access keys.
2. **State bucket**: an S3 bucket `ecommerce-agent-terraform-state-<account>` in `eu-central-1` with versioning,
   SSE-KMS and blocked public access. Locking uses S3-native lock files (Terraform 1.10+), no DynamoDB table.
3. **Certificate**: an ACM certificate in `eu-central-1` for the three host names (`app`, `api`, `auth`), validated
   by DNS. Its ARN goes to `certificate_arn`.
4. **Temporal Cloud**: namespaces `operator-staging` and `operator-production` in AWS `eu-central-1`, an API key
   per namespace (value goes to Secrets Manager, below).
5. **SES**: after the first apply, publish the DKIM CNAMEs from the output `ses_dkim_tokens` and request production
   access for the account (SES starts in the sandbox).
6. **Bedrock** (production): model access for the chosen Claude model in the EU and the ARNs of the EU
   cross-region inference profile in `bedrock_model_arns`.

## First apply of an environment

```bash
cd infra/terraform/environments/staging
cp backend.hcl.example backend.hcl            # fill in the account id
cp terraform.tfvars.example terraform.tfvars  # fill in hosts, certificate, Temporal, image tags
terraform init -backend-config=backend.hcl
terraform plan -out=staging.tfplan            # reviewed by the owner
terraform apply staging.tfplan                # only after approval
```

Then, in this order:

1. **Secrets.** Terraform creates the containers only, so no value is ever in the state. Put each value:

   ```bash
   aws secretsmanager put-secret-value --secret-id operator-staging/db-runtime \
     --secret-string '{"username":"operator_runtime","password":"<generated>"}'
   ```

   | Secret | Value |
   | --- | --- |
   | `db-runtime` | `{"username":"operator_runtime","password":…}` |
   | `db-migrator` | `{"username":"operator_migrator","password":…}` |
   | `keycloak-db` | `{"username":"keycloak","password":…}` |
   | `keycloak-admin` | `{"username":…,"password":…}` (bootstrap administrator; remove after creating a real one) |
   | `temporal-api-key` | the namespace's API key |
   | `integration-secrets-key` | `{"key":"<32 random bytes, base64>","key_id":"v1"}` |
   | `frontend` | `{"oidc_client_secret":…,"session_secret":"<at least 32 characters>"}` |
   | `anthropic-api-key` | staging only, synthetic data only; leave empty in production |

2. **Database roles** (runbooks: Bootstrapping the database roles). Run the administrative task with the master
   secret that RDS manages, executing `infra/postgres/init/02-operator-roles.sql` with the passwords from step 1 and
   creating the `keycloak` role and database:

   ```bash
   aws ecs run-task --cluster operator-staging --task-definition operator-staging-db-admin \
     --launch-type FARGATE --network-configuration 'awsvpcConfiguration={subnets=[…],securityGroups=[…],assignPublicIp=ENABLED}' \
     --overrides '{"containerOverrides":[{"name":"db-admin","command":["psql","-v","ON_ERROR_STOP=1","-c","…"]}]}'
   ```

   The subnets and the security group are in the output `task_network`.
3. **Images.** Push the backend and frontend images to the ECR repositories of the output `ecr_repositories`
   with the tags set in `terraform.tfvars`.
4. **Migrations.** Run the migration task (`migration_task_definition`) and wait for exit code 0 (ADR-0023 point 3).
5. **Keycloak realm.** Import the realm with the production redirect URIs (not the local one, which has local
   passwords and a password-grant client).
6. **DNS.** Point the three host names at `load_balancer_dns_name`.

## Every rollout

Push images with a new tag, run the migration task with that tag, then `terraform apply` with the new tags. The
services roll with a circuit breaker that returns to the previous task definition on failure.

## Not here yet

- CloudWatch alarms of ADR-0024 and the SNS subscription from the SES events to the backend (with the SES adapter).
- A NAT gateway (ADR-0023 alternative), Multi-AZ for production (`db_multi_az`, an owner's cost decision).
- The logical dump schedule (ADR-0022 point 7): the `db-admin` task and the dump bucket exist; the schedule comes
  with the restore drill.
