# ADR-0023: EU environments, deployment, Temporal Cloud and backups

- Status: Proposed
- Date: 2026-11-15
- Revised: 2026-11-15 (review: a separate migration step under the migration role; hosting chosen by the project
  owner: AWS eu-central-1 and Temporal Cloud in AWS Frankfurt)
- Related: plan decision I4 (Docker Compose locally, EU production, Temporal Cloud preferred), I2 (EU data
  residency of the model provider), I5 (OIDC, Keycloak), document 01 O-03, ADR-0022, epic E-46

## Context

Everything so far runs on a developer machine (`infra/docker-compose.yml`) and in CI. The pilot needs a staging and
a production environment in the EU, with the same code and only configuration in between. The hosting provider,
the Temporal Cloud account and the KMS were open when this ADR was first written. This ADR fixes the artifacts, the
process roles, the configuration contract, the start-up guard and the backup and restore procedure. On 2026-11-15
the project owner chose AWS in `eu-central-1` and Temporal Cloud in AWS Frankfurt (point 9). No paid resource is
created before the owner approves the plan and the cost estimate.

## Decision

1. **Three environments.** `local` (Compose, fixtures, local keys), `staging` (EU, the WooCommerce test store and the
   Meta test account, shadow mode) and `production` (EU, the pilot store). Staging and production are identical in
   shape and differ only in values and in the policy mode (ADR-0020: `EVALUATION_ONLY` until the L1 procedure of
   E-48 allows `EXECUTE_ON_APPROVAL`; per ADR-0025 the mode is a ceiling and the pilot store also needs its own
   action permission).
2. **Artifacts.** Two container images built from the repository root: `backend/Dockerfile` (one jar, runs as a
   non-root user) and `frontend/Dockerfile` (Next.js standalone server). The backend image runs as two processes:
   `SPRING_PROFILES_ACTIVE=cloud,api` (HTTP, scaled by load) and `cloud,worker` (Temporal workers, sweeps,
   observers and retention; at least one, more for throughput). Neither of them migrates the database.
3. **A separate migration step.** Each deployment runs the same image once as a one-off job with another main
   class, `MigrateCommand`:
   `java -Dloader.main=com.ecommerceagent.MigrateCommand -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher`.
   It connects as `operator_migrator` (ADR-0022) with `DB_URL`, `DB_MIGRATION_USERNAME` and
   `DB_MIGRATION_PASSWORD`, runs Flyway (`migrate`, then `validate`), starts no Spring context, web server, worker or
   scheduler, and exits with a non-zero status on any failure.
   - *Order of a deployment:* note the point-in-time recovery mark, then run the migration job and wait for success,
     then roll out the worker, then the API. A failed migration stops the rollout, and the running version keeps
     serving.
   - *Runtime processes* (`api`, `worker`) run with `spring.flyway.enabled=false` as `operator_runtime`. At start
     `SchemaVersionCheck` reads `flyway_schema_history` (`SELECT` only) and refuses to start when a migration of the
     image is pending, a migration failed, or the history is not readable. A newer schema is accepted, because of
     the next rule.
   - *Migrations are backward compatible with the previous release* (expand, then contract in a later release):
     during a rollout the old version runs on the new schema. A destructive change ships in two releases.
   - *Locally and in tests* the backend is not in Compose. It migrates on start, but with the migrator's own
     connection (`spring.flyway.user`), and serves requests as the runtime role, so the role split is the same
     everywhere.
4. **Configuration contract.** Profile `cloud` (`application-cloud.yml`) takes every address and secret from the
   environment, with no defaults: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `OIDC_ISSUER_URI`, `TEMPORAL_ADDRESS`,
   `TEMPORAL_NAMESPACE`, `TEMPORAL_API_KEY`, `INTEGRATION_SECRETS_KEY`, `INTEGRATION_SECRETS_KEY_ID`,
   `WEBHOOK_BASE_URL`, `OPERATOR_APP_BASE_URL`, `ANTHROPIC_API_KEY` (when the agent is enabled) and the OTLP
   endpoints. Secrets come from the platform's secret store, never from the image or the repository.
   `DB_USERNAME` and `DB_PASSWORD` are the runtime role's. Only the migration job gets `DB_MIGRATION_USERNAME` and
   `DB_MIGRATION_PASSWORD`. `CloudConfigurationCheck` stops the start before any bean connects anywhere when:
   - a value is missing or points at a local address;
   - a local, test or e2e profile is mixed in;
   - an `api` or `worker` process is given the migration credentials or does not have `spring.flyway.enabled=false`;
   - `operator.row-security.enabled=false` is set (ADR-0022).
5. **Temporal Cloud.** One namespace per environment in an EU region, API key authentication over TLS
   (`spring.temporal.connection.api-key`, `enable-https`); mTLS certificates are the alternative the starter
   supports without code changes. Workflow history retention of 30 days. Payloads stay small (ADR-0016: references
   and hashes, not snapshots), so no payload codec is needed for the pilot; if the data residency review (O-03)
   requires it, an encrypting codec is added before production.
6. **Data in the EU.** PostgreSQL 17 managed in an EU region with encryption at rest, Keycloak in the same region
   with its own database, object storage for backups in the EU. The model provider's EU processing is verified
   separately (I2) and is a precondition for turning the agent on in production: real store data reaches Claude
   only through Amazon Bedrock with EU processing confirmed in its terms, and the direct Anthropic API is used only
   locally with synthetic data (the project owner's decision of 2026-11-15).
7. **Backups.** The managed database's point-in-time recovery (at least 14 days) is the primary backup.
   `infra/backup/backup.sh` makes an independent daily logical dump (custom format with grants, plus its SHA-256)
   kept 30 days in EU object storage. `infra/backup/restore-check.sh` restores a dump into a scratch database and
   checks the migrations, row-level security policies and the runtime role's grants; it runs monthly and after every
   schema change in staging (restore drill). Targets for the pilot: RPO 15 minutes (PITR), RTO 4 hours.
8. **Keys.** `INTEGRATION_SECRETS_KEY` is generated per environment, stored in the secret store and rotated as in
   ADR-0022 point 4. Envelope encryption with the provider's KMS replaces where the key bytes come from when the
   provider is chosen; the stored format does not change.

9. **Hosting: AWS `eu-central-1`.** Infrastructure as code in `infra/terraform` (Terraform), one root per
   environment over shared modules. It is planned and reviewed, and applied only after the owner approves the plan
   and the estimate in `docs/operations/aws-cost-estimate.md`.
   - *Compute:* ECS on Fargate, one cluster per environment. Services `api`, `worker`, `frontend` and `keycloak`; the
     migration step of point 3 is a one-off ECS task with its own task role and the migration secret only. Images in
     ECR.
   - *Network:* a VPC per environment over two availability zones. An application load balancer with an ACM
     certificate is the only entry point. For the pilot the tasks run in public subnets with a public IP and a
     security group that accepts traffic only from the load balancer, instead of a NAT gateway (they need outbound
     access to WooCommerce, Meta, Temporal Cloud and the model provider). The database is in private subnets and
     reachable only from the tasks' security group.
   - *Database:* RDS for PostgreSQL 17, encrypted with a customer-managed KMS key, point-in-time recovery of 14 days,
     deletion protection, no public access. The operator's database and Keycloak's database are separate databases
     on the same instance in the pilot. Single-AZ in staging; production starts single-AZ and moves to Multi-AZ
     before the pilot leaves L1 shadow (a cost decision of the owner).
   - *Secrets and keys:* Secrets Manager for every secret of point 4, injected into the task definitions by ARN.
     KMS keys per environment for RDS, S3, Secrets Manager and, with E-46, the envelope key of point 8.
   - *Storage:* an S3 bucket per environment for the logical dumps of point 7, SSE-KMS, public access blocked,
     versioning, and a lifecycle rule that deletes after 30 days.
   - *Observability:* CloudWatch Logs for the containers (30 days), and an OpenTelemetry collector (ADOT) in each
     task sending metrics and traces to CloudWatch and X-Ray. The alerts of ADR-0024 are CloudWatch alarms.
   - *Mail:* Amazon SES in `eu-central-1` for notifications (a verified domain with DKIM, a configuration set whose
     events go to SNS and back to the delivery events of ADR-0021). The customer support mailbox gets its own
     `MailProvider` adapter later; SES sending is not used for it.
   - *Temporal Cloud:* AWS `eu-central-1` (Frankfurt), one namespace for staging and one for production, API keys
     per namespace in Secrets Manager.
   - *State:* Terraform state in a versioned, encrypted S3 bucket with S3-native locking (no DynamoDB table),
     created once by hand (documented in `infra/terraform/README.md`).

## Alternatives considered

- **A NAT gateway per environment.** Private subnets for the tasks are the usual shape, but a NAT gateway costs
  more per month than the pilot's compute. The security group gives the same inbound protection; the NAT gateway
  can be added later without changing the services.
- **Self-hosted Temporal.** Possible with the same configuration, but it adds a database and upgrades to run; I4
  prefers Temporal Cloud.
- **Flyway on application start (the first draft of this ADR).** No extra job, but the runtime would need the
  owner's rights to run DDL, which contradicts ADR-0022, and every replica would race for the migration lock.
- **Separate images for API and worker.** Same code and dependencies; one image keeps the versions in lockstep.

## Consequences

- Staging waits for the owner's approval of the Terraform plan and the cost estimate, and for the Temporal Cloud
  namespaces and the Meta test account, which the owner prepares.
- CI builds both images on every change (without pushing); publishing to ECR comes with the first apply.
- The deployment pipeline of the chosen provider must support a one-off job that gates the rollout (a Kubernetes Job,
  a Cloud Run job, an ECS task, or a release command).
- The restore drill is an operational duty with an owner (E-47 runbooks).

## Implementation status

Implemented on the pilot branch: `MigrateCommand`, `SchemaVersionCheck`, `spring.flyway.enabled=false` in the cloud
profile, the new configuration rules, and migrations as the migrator locally and in tests.

## Verification

- `CloudConfigurationCheckTest`: these refuse the start, and a complete configuration passes:
  - missing values, unresolved placeholders and local addresses;
  - mixed profiles;
  - migration credentials or Flyway on a runtime process;
  - RLS switched off.
- `MigrationStepTest`:
  - `MigrateCommand` applies all migrations as the migrator, which then owns the tables.
  - The runtime role cannot migrate.
  - A runtime process refuses an older schema and starts on the current one.
- CI job "Container images" builds both images.
- The backup and restore scripts were run against a scratch PostgreSQL 17 with all migrations: the restore check
  passes and refuses the database named `operator`.
