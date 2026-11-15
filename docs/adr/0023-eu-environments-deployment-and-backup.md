# ADR-0023: EU environments, deployment, Temporal Cloud and backups

- Status: Proposed
- Date: 2026-11-15
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
   E-48 allows `EXECUTE_ON_APPROVAL` for the pilot store).
2. **Artifacts.** Two container images built from the repository root: `backend/Dockerfile` (one jar, runs as a
   non-root user) and `frontend/Dockerfile` (Next.js standalone server). The backend image runs as two processes:
   `SPRING_PROFILES_ACTIVE=cloud,api` (HTTP, scaled by load) and `cloud,worker` (Temporal workers, sweeps,
   observers and retention; at least one, more for throughput). Flyway migrates on start of either; one migration
   runs at a time (Flyway's lock).
3. **Configuration contract.** Profile `cloud` (`application-cloud.yml`) takes every address and secret from the
   environment, with no defaults: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `OIDC_ISSUER_URI`, `TEMPORAL_ADDRESS`,
   `TEMPORAL_NAMESPACE`, `TEMPORAL_API_KEY`, `INTEGRATION_SECRETS_KEY`, `INTEGRATION_SECRETS_KEY_ID`,
   `WEBHOOK_BASE_URL`, `OPERATOR_APP_BASE_URL`, `ANTHROPIC_API_KEY` (when the agent is enabled) and the OTLP
   endpoints. Secrets come from the platform's secret store, never from the image or the repository.
   `CloudConfigurationCheck` stops the start when a value is missing, points at a local address, or a local, test or
   e2e profile is mixed in, before any bean connects anywhere.
4. **Temporal Cloud.** One namespace per environment in an EU region, API key authentication over TLS
   (`spring.temporal.connection.api-key`, `enable-https`); mTLS certificates are the alternative the starter
   supports without code changes. Workflow history retention of 30 days. Payloads stay small (ADR-0016: references
   and hashes, not snapshots), so no payload codec is needed for the pilot; if the data residency review (O-03)
   requires it, an encrypting codec is added before production.
5. **Data in the EU.** PostgreSQL 17 managed in an EU region with encryption at rest, Keycloak in the same region
   with its own database, object storage for backups in the EU. The model provider's EU processing is verified
   separately (I2) and is a precondition for turning the agent on in production.
6. **Backups.** The managed database's point-in-time recovery (at least 14 days) is the primary backup.
   `infra/backup/backup.sh` makes an independent daily logical dump (custom format with grants, plus its SHA-256)
   kept 30 days in EU object storage. `infra/backup/restore-check.sh` restores a dump into a scratch database and
   checks the migrations, row-level security policies and the store role's grants; it runs monthly and after every
   schema change in staging (restore drill). Targets for the pilot: RPO 15 minutes (PITR), RTO 4 hours.
7. **Keys.** `INTEGRATION_SECRETS_KEY` is generated per environment, stored in the secret store and rotated as in
   ADR-0022 point 4. Envelope encryption with the provider's KMS replaces where the key bytes come from when the
   provider is chosen; the stored format does not change.

## Alternatives considered

- **Self-hosted Temporal.** Possible with the same configuration, but it adds a database and upgrades to run; I4
  prefers Temporal Cloud.
- **Infrastructure as code for one provider now.** It would encode a provider choice that has not been made; the
  configuration contract above is what any provider's IaC fills in.
- **Separate images for API and worker.** Same code and dependencies; one image keeps the versions in lockstep.

## Consequences

- Choosing the hosting provider, the KMS and the Temporal Cloud account (with their EU regions) is still open and
  blocks staging; nothing in the code needs to change for it.
- CI builds both images on every change (without pushing); publishing to a registry comes with the provider.
- The restore drill is an operational duty with an owner (E-47 runbooks).

## Verification

- `CloudConfigurationCheckTest`: missing values, unresolved placeholders, local addresses and mixed profiles refuse
  the start; a complete configuration passes.
- CI job "Container images" builds both images.
- The backup and restore scripts were run against a scratch PostgreSQL 17 with all migrations: the restore check
  passes and refuses the database named `operator`.
