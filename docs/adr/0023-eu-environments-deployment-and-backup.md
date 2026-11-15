# ADR-0023: EU environments, deployment, Temporal Cloud and backups

- Status: Proposed
- Date: 2026-11-15
- Revised: 2026-11-15 (review: a separate migration step under the migration role)
- Related: plan decision I4 (Docker Compose locally, EU production, Temporal Cloud preferred), I2 (EU data
  residency of the model provider), I5 (OIDC, Keycloak), document 01 O-03, ADR-0022, epic E-46

## Context

Everything so far runs on a developer machine (`infra/docker-compose.yml`) and in CI. The pilot needs a staging and
a production environment in the EU, with the same code and only configuration in between. The hosting provider,
the Temporal Cloud account and the KMS are not chosen yet, and no credentials exist for them. This ADR fixes what
does not depend on that choice: the artifacts, the process roles, the configuration contract, the start-up guard
and the backup and restore procedure, so that choosing a provider is a matter of filling in values.

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
3. **A separate migration step.** Each deployment runs the same image once as a one-off job with
   `SPRING_PROFILES_ACTIVE=cloud,migrate`. It connects as `operator_migrator` (ADR-0022) with `DB_MIGRATION_USERNAME`
   and `DB_MIGRATION_PASSWORD`, runs Flyway (`migrate`, then `validate`), starts no web server, worker or scheduler,
   and exits with a non-zero status on any failure.
   - *Order of a deployment:* note the point-in-time recovery mark, then run the migration job and wait for success,
     then roll out the worker, then the API. A failed migration stops the rollout, and the running version keeps
     serving.
   - *Runtime processes* (`api`, `worker`) run with `spring.flyway.enabled=false` as `operator_runtime`. At start
     they read `flyway_schema_history` (they have `SELECT` only) and refuse to start when the schema is older than
     the newest migration in the image, or when a migration failed. A newer schema is accepted, because of the next
     rule.
   - *Migrations are backward compatible with the previous release* (expand, then contract in a later release):
     during a rollout the old version runs on the new schema. A destructive change ships in two releases.
   - *Locally and in tests* the same split applies with the bootstrap roles of ADR-0022. Compose has a `migrate`
     service that the others depend on, and Testcontainers runs the migration as the migrator before the context
     connects as the runtime role.
4. **Configuration contract.** Profile `cloud` (`application-cloud.yml`) takes every address and secret from the
   environment, with no defaults: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `OIDC_ISSUER_URI`, `TEMPORAL_ADDRESS`,
   `TEMPORAL_NAMESPACE`, `TEMPORAL_API_KEY`, `INTEGRATION_SECRETS_KEY`, `INTEGRATION_SECRETS_KEY_ID`,
   `WEBHOOK_BASE_URL`, `OPERATOR_APP_BASE_URL`, `ANTHROPIC_API_KEY` (when the agent is enabled) and the OTLP
   endpoints. Secrets come from the platform's secret store, never from the image or the repository.
   `DB_USERNAME` and `DB_PASSWORD` are the runtime role's. Only the migration job gets `DB_MIGRATION_USERNAME` and
   `DB_MIGRATION_PASSWORD`. `CloudConfigurationCheck` stops the start before any bean connects anywhere when:
   - a value is missing or points at a local address;
   - a local, test or e2e profile is mixed in;
   - `migrate` is combined with `api` or `worker`;
   - an `api` or `worker` process is given the migration credentials or `spring.flyway.enabled=true`;
   - `operator.row-security.enabled=false` is set (ADR-0022).
5. **Temporal Cloud.** One namespace per environment in an EU region, API key authentication over TLS
   (`spring.temporal.connection.api-key`, `enable-https`); mTLS certificates are the alternative the starter
   supports without code changes. Workflow history retention of 30 days. Payloads stay small (ADR-0016: references
   and hashes, not snapshots), so no payload codec is needed for the pilot; if the data residency review (O-03)
   requires it, an encrypting codec is added before production.
6. **Data in the EU.** PostgreSQL 17 managed in an EU region with encryption at rest, Keycloak in the same region
   with its own database, object storage for backups in the EU. The model provider's EU processing is verified
   separately (I2) and is a precondition for turning the agent on in production.
7. **Backups.** The managed database's point-in-time recovery (at least 14 days) is the primary backup.
   `infra/backup/backup.sh` makes an independent daily logical dump (custom format with grants, plus its SHA-256)
   kept 30 days in EU object storage. `infra/backup/restore-check.sh` restores a dump into a scratch database and
   checks the migrations, row-level security policies and the runtime role's grants; it runs monthly and after every
   schema change in staging (restore drill). Targets for the pilot: RPO 15 minutes (PITR), RTO 4 hours.
8. **Keys.** `INTEGRATION_SECRETS_KEY` is generated per environment, stored in the secret store and rotated as in
   ADR-0022 point 4. Envelope encryption with the provider's KMS replaces where the key bytes come from when the
   provider is chosen; the stored format does not change.

## Alternatives considered

- **Self-hosted Temporal.** Possible with the same configuration, but it adds a database and upgrades to run; I4
  prefers Temporal Cloud.
- **Infrastructure as code for one provider now.** It would encode a provider choice that has not been made; the
  configuration contract above is what any provider's IaC fills in.
- **Flyway on application start (the first draft of this ADR).** No extra job, but the runtime would need the
  owner's rights to run DDL, which contradicts ADR-0022, and every replica would race for the migration lock.
- **Separate images for API and worker.** Same code and dependencies; one image keeps the versions in lockstep.

## Consequences

- Choosing the hosting provider, the KMS and the Temporal Cloud account (with their EU regions) is still open and
  blocks staging; nothing in the code needs to change for it.
- CI builds both images on every change (without pushing); publishing to a registry comes with the provider.
- The deployment pipeline of the chosen provider must support a one-off job that gates the rollout (a Kubernetes Job,
  a Cloud Run job, an ECS task, or a release command).
- The restore drill is an operational duty with an owner (E-47 runbooks).

## Implementation status

On the pilot branch both processes run Flyway on start with one database user, and there is no `migrate` profile.
After this revision is approved, a follow-up adds the profile, the start-up schema check, the new configuration
rules, the Compose `migrate` service and the test set-up with two roles, together with the role change of ADR-0022.

## Verification

- `CloudConfigurationCheckTest`: these refuse the start, and a complete configuration passes:
  - missing values, unresolved placeholders and local addresses;
  - mixed profiles, including `migrate` with `api` or `worker`;
  - migration credentials or Flyway on a runtime process;
  - RLS switched off.
- Migration step test: the `migrate` profile applies all migrations as the migrator and exits. A runtime process on
  an older schema refuses to start, and one on the current or a newer schema starts.
- CI job "Container images" builds both images.
- The backup and restore scripts were run against a scratch PostgreSQL 17 with all migrations: the restore check
  passes and refuses the database named `operator`.
