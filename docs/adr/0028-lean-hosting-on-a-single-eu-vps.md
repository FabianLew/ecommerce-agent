# ADR-0028: Lean hosting on a single EU VPS for the demo and the first pilot

- Status: Proposed
- Date: 2026-11-15
- Related: ADR-0023 (deployment contract; its point 9, AWS hosting, becomes a future option), ADR-0022 (database
  roles, row-level security, keys, retention), ADR-0024 (metrics and alerts), ADR-0021 (notifications), ADR-0016
  and ADR-0027 (model provider and AI cost governance), the project owner's decision of 2026-11-15 to lower fixed
  costs before product validation

## Context

The AWS plan of ADR-0023 point 9 (ECS Fargate, RDS, KMS, Secrets Manager, CloudWatch, two permanent environments) is
estimated at about USD 400–425 a month (`docs/operations/aws-cost-estimate.md`). That is too much for an MVP without
customers. On 2026-11-15 the project owner set new constraints:

- development stays local on Docker Compose;
- the demo runs on one VPS in the EU, preferably Hetzner in Germany, at EUR 25–45 a month fixed, excluding model
  and API usage;
- Temporal: the dev server locally only, Temporal Cloud Pay-As-You-Go for the demo and the pilot; the dev server is
  never a production component;
- the AWS Terraform stays in the repository as a future option, neither applied nor deleted;
- no two permanently running staging and production environments;
- Bedrock and SES stay optional, usage-billed adapters.

The application must not change: the same images, the `cloud` profile and `CloudConfigurationCheck`, the separate
migration step, the database roles and row-level security of ADR-0022, the execution safeguards of ADR-0020 and the
AI cost governance of ADR-0027.

## Decision

1. **One Hetzner Cloud server in Germany per environment, running Docker Compose.** The recommended size for the demo
   and the start of the pilot is CX43 (8 shared vCPU, 16 GB RAM, 160 GB disk; EUR 15.99 a month net, price list after
   the changes of 15 June 2026). CX33 (EUR 8.49) is the minimum for a demo without on-server monitoring. A dedicated
   vCPU server (CCX23, EUR 85.99) is not required; it is chosen only when the load tests of point 9 or reliability
   requirements call for it. Images are built for x86_64.
2. **Compose topology.** One Compose network. `caddy` is the only container that publishes ports (80 and 443).
   `frontend`, `api`, `worker` (the same backend image in two roles), `keycloak` (production mode), `postgres` (17,
   databases `operator` and `keycloak`) and the backup job publish nothing. `migrate` is a one-off container run
   before `api` and `worker` start, with the migration role only (ADR-0023 point 3). Temporal is Temporal Cloud; the
   Temporal dev server is not part of the server's Compose file.
3. **Network and access.** The Hetzner Cloud Firewall admits only 80 and 443 from the internet; SSH is closed to the
   internet and reachable only over Tailscale (or WireGuard) with keys, no passwords, no root login. `ufw` on the host
   as a second layer; because Docker bypasses `ufw` for published ports, nothing but Caddy publishes a port, and a
   port scan follows every deployment. The Keycloak admin console and any monitoring UI are reachable only over the
   VPN. Caddy obtains Let's Encrypt certificates, sets HSTS and redirects HTTP to HTTPS.
4. **Secrets without KMS.** The source of truth is a SOPS file encrypted with age in a private operations repository,
   never in the application repository. The age private key is held by the owner in a password manager and in an
   offline copy; a second recipient key allows recovery when one copy is lost. On the server, secrets are files with
   mode 0600 owned by root, outside any repository. Rotation: change in the SOPS file, deploy, restart only the
   containers that use the secret, revoke the old value after a smoke test; two-step rotation for database role
   passwords, the integration envelope key (ADR-0022 point 5) and the OIDC client secret. Mandatory rotation when a
   person with access leaves, on a suspected leak, on loss of a key copy, and yearly for bucket and Temporal Cloud
   keys. Moving to a KMS later changes where the key bytes come from, not the stored format.
5. **Backups off the server.**
   - *Demo:* nightly `pg_dump` (custom format) of `operator` and `keycloak`, encrypted with age on the server before
     upload, to Hetzner Object Storage in another location; 30 days; RPO up to 24 hours.
   - *Pilot:* pgBackRest with a weekly full, nightly incremental and continuous WAL archiving to the same storage,
     repository encryption with a passphrase kept outside the server, PITR of 14 days (ADR-0023 point 7); the nightly
     age-encrypted `pg_dump` stays as an independent second path; Hetzner server backups as a third layer. The
     database data lives on a LUKS-encrypted volume.
   - The server's storage credentials can write and read one bucket and cannot delete objects or versions; deletion
     is done by bucket lifecycle rules or by an administrative key that is never on the server. Versioning is on;
     lifecycle rules delete dumps after 30 days and noncurrent versions 7 days later; pgBackRest expires its own
     backups and WAL. Data deleted in the application leaves every copy within the backup retention. A legal hold
     that covers backups moves the named dump to a prefix without a lifecycle rule, with an operations log entry.
   - Each backup run reports to an external dead-man switch; a missing report is an alert.
6. **Restore and disaster recovery.** Restores never target the live database: a temporary hourly-billed server or
   a local Docker. PostgreSQL is restored with `pg_restore` (demo) or pgBackRest to a point in time (pilot); `migrate`
   must then finish with exit code 0 and no change, and the role bootstrap of the ADR-0022 runbook verifies roles and
   grants. Keycloak is restored with its database; a realm export (without user passwords) in the operations
   repository is the fallback. The application configuration (`compose.prod.yml`, `Caddyfile`, `.env` with image
   tags, the SOPS file) is in the operations repository and the images in GHCR, so the server holds nothing that
   cannot be rebuilt. Temporal state is in Temporal Cloud. Total loss of the server: rebuild from the bootstrap
   script, decrypt the secrets, restore the database, migrate, start, switch DNS (TTL 300 s), smoke test, rotate the
   secrets that were on the lost server.
7. **RTO and RPO are measured, not assumed.** Targets: demo RPO 24 h and RTO 8 h; pilot RPO 15 min and RTO 4 h. The
   values that count are the measured results of drills recorded in the operations log. A database restore drill runs
   monthly and after a change to the backup mechanism; a full loss-of-server drill runs before the pilot starts and
   quarterly after. A failed drill, or a result worse than the target, blocks the pilot start.
8. **Monitoring sized to the stage.**
   - *Demo:* an external uptime check (free plan) on `/actuator/health` and the login page; the dead-man switch for
     backups; a cron script every 5 minutes that reports a failure to the dead-man switch when disk use passes 80%, a
     container is unhealthy or restarting, or health fails; Hetzner console graphs. OTLP metric export is off.
   - *Pilot:* in addition, Prometheus with its OTLP receiver and Alertmanager on the server with the rules of
     ADR-0024, node-exporter and cAdvisor for host and container metrics (replacing CloudWatch), CPU steal alert above
     10% for 15 minutes; Grafana optional and VPN-only. Logs are JSON on stdout with the `local` driver and rotation.
9. **Load tests before the real pilot.** On a temporary CX43 with the pilot configuration: API (Decision Inbox,
   decision detail, login), workers (history import and the Inventory Guardian cycle for several stores at once),
   PostgreSQL (query times, connections, I/O during backup) and Keycloak (parallel logins). Pass criteria: API p95
   below 500 ms, no Temporal task queue backlog, CPU steal below 10%, memory below 80% of the container limits.
10. **Deployment and rollback.** GitHub Actions builds the images tagged with the commit SHA and pushes them to GHCR.
    A deployment is started by hand (an environment with required approval), connects over Tailscale SSH, pulls the
    images, runs `migrate`, then starts `api`, `worker` and `frontend`, and runs a smoke test (health, login, one
    Temporal round trip). Rollback means the previous image tag; migrations are forward-only and expand/contract
    (ADR-0023 point 3), so the previous version runs on the new schema. A database restore is the last resort. The
    mutation stop (ADR-0020) and `operator.execution.mutations-enabled=false` do not depend on a deployment.
11. **Hosting and AI cost limits are separate mechanisms.** A cheaper server does not limit model spending; only
    ADR-0027 does. On the server the agent is off (`OPERATOR_AGENT_ENABLED=false`) and no model API key is in the
    secrets until ADR-0027 stage 1 is deployed; with stage 1, `CloudConfigurationCheck` refuses to start with the
    agent on and governance not configured. The AI kill switch of ADR-0027 works without a deployment. The provider's
    own spend limit (Anthropic console, an AWS budget alert for Bedrock) is an extra outer layer, not a replacement.
    Each environment has its own model API key.
12. **Demo and pilot are separate.** The demo uses synthetic data only (test WooCommerce stores, the Meta test
    account, the direct Claude API). The pilot has its own server, database, secrets, Temporal Cloud namespace and
    Keycloak realm. Running both at once means two servers; the demo is stopped or moved to CX33 when the pilot
    starts. A deployment rehearsal runs on a temporary hourly-billed server instead of a permanent staging
    environment.
13. **Entry conditions for the pilot with real data:** GDPR review and data processing agreements with Hetzner,
    Temporal and the model provider; Bedrock with EU processing for real data (no real data to a model before the
    provider's terms are verified); ADR-0027 stage 1 deployed before the agent is switched on for the pilot store;
    the load tests of point 9 passed; pgBackRest PITR with passed restore and loss-of-server drills; retention
    enabled after checking the O-03 periods; the rehearsal and `docs/operations/pilot-procedure.md` completed;
    ad execution only after the store owner's separate L1 consent, L2 still off.
14. **The AWS Terraform stays a future option.** `infra/terraform` and `docs/operations/aws-cost-estimate.md` stay
    in the repository, marked as not in use; the CI `terraform` job keeps validating them. Nothing in the application
    depends on AWS, so a later move is: the same images on ECS, `pg_dump`/`pg_restore` or logical replication to
    RDS, secrets from SOPS to Secrets Manager with KMS, the same Temporal Cloud namespace, a DNS switch. An
    intermediate step without AWS (a separate database server or a managed PostgreSQL in the EU) is also possible.
15. **Files.** After the owner approves this ADR: `infra/vps/` with `compose.prod.yml`, `Caddyfile`, the server
    bootstrap (cloud-init), backup scripts (age-encrypted `pg_dump`, pgBackRest configuration), the health cron
    script, and the deployment workflow; `docs/operations/vps-cost-estimate.md`; runbook sections for restore, secret
    rotation, loss of the server, drills and load tests. No server, bucket or Temporal Cloud account is bought, and
    nothing is deployed, without the owner's explicit confirmation.

## Cost estimate (net, per month)

| Item | Demo minimum | Demo recommended | Pilot start | Pilot scaled up (option) |
|---|---|---|---|---|
| Server (official price list) | CX33: 8.49 | CX43: 15.99 | CX43: 15.99 | CCX23: 85.99 |
| Hetzner server backups (+20%) | 1.70 | 3.20 | 3.20 | 17.20 |
| Public IPv4 (estimate) | 0.5–1 | 0.5–1 | 0.5–1 | 0.5–1 |
| Object Storage (estimate) | 5–6 | 5–6 | 6–8 | 6–8 |
| Encrypted 50 GB volume (estimate) | 0 | 0 | 2–3 | 2–3 |
| Domain | 1 | 1 | 1 | 1 |
| **Total** | **about 17–18** | **about 26–27** | **about 30–34** | **about 110–115** |

Usage-billed and outside the target: Temporal Cloud PAYG, the model provider (bounded by ADR-0027), e-mail (SES or
another EU SMTP provider, optional). Server prices were verified by the owner; the other items are estimates to be
confirmed before purchase.

## Alternatives considered

- **AWS as in ADR-0023 point 9.** Managed services, KMS and high availability, but about USD 400–425 a month fixed
  before any customer. Kept as the future path.
- **Dedicated vCPU (CCX23) from the start.** Predictable CPU, but more than five times the server price; a small
  pilot does not need it unless the load tests show CPU steal or latency problems.
- **The Temporal dev server on the VPS.** No cost, but it is not built for production durability; workflow state
  would share the single server's fate. Rejected by the owner.
- **Self-hosted Temporal on the VPS.** No usage cost, but a second stateful system to back up and upgrade on one
  server. Rejected for the MVP.
- **A full observability stack on the demo.** Rejected: an external uptime check, the dead-man switch and a health
  script detect the failures that matter for the demo at no extra memory cost.
- **A permanent staging server.** Doubles the fixed cost; a temporary server per rehearsal gives the same check.

## Consequences

- Fixed cost falls from about USD 400–425 to about EUR 26–34 a month.
- A single point of failure: no high availability, maintenance windows mean short outages, scaling is vertical only.
  Recovery relies on drills with measured RTO and RPO.
- No KMS or HSM: key custody is the owner's responsibility (two copies of the age key, quarterly recovery check).
- Shared vCPU can slow down under neighbours' load; monitored on the pilot, and the move to a dedicated server is a
  resize of a few minutes.
- Operational duties (OS updates, drills, rotation) sit with one person; the procedures are written so that a second
  person can follow them.

## Verification

- Before the demo: a port scan shows only 80 and 443; SSH refused from the internet; a deployment and a rollback
  rehearsed on a temporary server; the uptime check and dead-man switch fire when the server or backup is stopped.
- Before the pilot: the drills and load tests of points 7 and 9 with recorded results.
