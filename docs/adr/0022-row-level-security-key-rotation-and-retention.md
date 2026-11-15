# ADR-0022: Row-level security, key rotation and data retention

- Status: Proposed
- Date: 2026-11-15
- Related: ADR-0004 points 8 and 9, ADR-0005 point 5, ADR-0007 (`key_id`), ADR-0009 (inbox and outbox growth),
  ADR-0016 (agent run steps), ADR-0021 (notifications), document 01 O-03, document 02 §Security, epic E-45

## Context

Store isolation is enforced in the application: every repository method takes a `TrustedStoreScope` and filters by
`organization_id` and `store_id` (ADR-0004). ADR-0004 point 9 promised PostgreSQL row-level security as a second
line of defense before the pilot, so that a forgotten filter in a new query cannot return another store's rows.
Two more pilot prerequisites are open: integration credentials are sealed with one static key and cannot be
rotated (ADR-0007 kept a `key_id` column for this), and nothing removes data that has served its purpose: the
webhook inbox and outbox grow without limit, agent run steps keep prompt and response content forever and the
audit has no retention at all (ADR-0004 point 8).

Two facts shape the design. Superusers and table owners bypass RLS, and the application currently connects as the
role that owns the tables (locally and in Testcontainers a superuser). And work that is not tied to one store
(Flyway, the inbox processor, sweeps, the outcome observer that walks all stores) must keep working.

## Decision

1. **A store role, switched on per connection (RLS).** Migration V23 creates the `NOLOGIN` role `operator_app`,
   grants it to the migrating role, and grants it `SELECT, INSERT, UPDATE, DELETE` on the application tables
   (default privileges cover future tables), except `flyway_schema_history` (none) and `audit_event`
   (`SELECT, INSERT` only, ADR-0004 point 8).
2. **Policies.** Every table with `store_id` and `organization_id` gets RLS enabled and one policy `store_isolation`
   for `operator_app`: a row is visible and writable only when both columns equal the session settings
   `app.store_id` and `app.organization_id`. An unset value matches nothing. `audit_event` has a read policy for
   the bound store and an insert policy that also accepts entries without a store. Table owners are not forced
   through RLS, so unbound work keeps its current rights. A schema test fails when a new store table lacks RLS
   or a policy.
3. **Binding.** `StoreRowGuard` (identity) holds the verified scope of the current thread. It is bound only where a
   `TrustedStoreScope` is built for a request or an activity: the web scope resolver (cleared after the request by
   an interceptor) and the Temporal activity interceptor (cleared in `finally`). `RowSecurityDataSource` wraps the
   application `DataSource` and, on every connection checkout, sets `role`, `app.store_id` and
   `app.organization_id` in one statement: `operator_app` and the scope when bound, the login role and empty values
   when not. Connections are checked out in autocommit mode, so the settings never leak into a later checkout.
   It can be switched off with `operator.row-security.enabled=false` (for diagnosis only).
4. **Credentials key ring.** `SecretCipher` seals with the current key (`operator.integration.secrets-key` and
   `secrets-key-id`) and opens with the current key or any key listed in
   `operator.integration.previous-secrets-keys` (`keyId → base64`). A credential sealed with a previous key is
   resealed with the current key the next time it is opened (`IntegrationConnections`, `MetaConnections`), so a
   rotation is: add the new key as current, move the old one to the previous list, remove it once no row uses it
   (a count by `key_id` is logged at start). Envelope encryption with a cloud KMS is chosen with the hosting in
   E-46: it replaces where the key bytes come from, not the format.
5. **Retention.** Each module that owns data declares a `RetentionTask` (identity interface) with a name and a
   period from `operator.retention.periods`. `RetentionRunner` (worker profile) runs them every
   `operator.retention.interval` in batches of `operator.retention.batch-size` and records one
   `RETENTION_APPLIED` audit entry per task with the count. The pilot defaults, to be confirmed with O-03:

   | Data | Period | Action |
   |---|---|---|
   | `webhook_inbox` rows no longer `RECEIVED` | 30 days | delete |
   | `outbox_event` rows already dispatched | 30 days | delete |
   | `agent_run_step.payload` (prompts, responses, tool results) | 90 days | replaced by `{"redacted": true}`; hashes, tokens and cost stay on `agent_run` |
   | `operator_notification` with its read marks and finished deliveries | 180 days | delete |
   | `audit_event` | 400 days | delete |

   The runner is **off by default** (`operator.retention.enabled=false`) until the periods are approved.
   The audit trigger allows a `DELETE` only inside a transaction that set `operator.audit_retention = 'on'`, and
   only for rows older than 365 days, so neither a bug nor a short misconfigured period can remove recent audit.
   Decisions, evidence, snapshots, approvals, authorized actions, the execution ledger and outcomes are kept:
   they are the record of why an action happened.

## Alternatives considered

- **A separate login role for the application.** The strongest form, but it needs a second set of credentials in
  every environment and a Flyway data source of its own; the switched role gives the same policy checks for store
  work now, and E-46 can move to a separate login without changing the policies.
- **`FORCE ROW LEVEL SECURITY` with a permissive policy when unset.** Protects nothing for the work that matters:
  a forgotten binding would silently fall back to "everything visible".
- **`SET LOCAL` per transaction.** Every query outside a transaction (most `JdbcClient` reads) would run without
  the settings.
- **One retention job that knows every table.** Spreads knowledge of other modules' tables into one place; module
  tasks keep each table with its owner.

## Consequences

- Migration V23 (role, grants, policies, audit trigger) on the pilot branch, renumbered when it is merged.
- Every new store table needs `ENABLE ROW LEVEL SECURITY` and a `store_isolation` policy in its migration; the schema
  test enforces it.
- One extra statement per connection checkout.
- Creating the role requires `CREATEROLE` for the migrating user; roles are cluster-wide, so environments sharing a
  cluster share the role name.
- Code that legitimately works across stores must run unbound (jobs), never inside a request or an activity.

## Verification

- Schema test: RLS and a policy on every store table, the app role's privileges, no `UPDATE`/`DELETE` on the audit.
- Isolation test: with store A bound, an unfiltered query sees only A's rows and an insert for store B is refused;
  unbound work sees everything; the binding does not outlive a request. Workflow tests run their activities under
  the binding.
- The full test suite runs with RLS on.
- Cipher tests: open with a previous key, reseal on open, unknown key refused.
- Retention tests: each task removes or redacts only expired rows, the audit trigger refuses recent rows and
  deletes outside the retention transaction.
