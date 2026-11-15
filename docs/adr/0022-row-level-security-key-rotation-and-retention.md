# ADR-0022: Row-level security, key rotation and data retention

- Status: Proposed
- Date: 2026-11-15
- Revised: 2026-11-15 (review: separate migration and runtime roles, fail-closed scope, system operations)
- Related: ADR-0004 points 8 and 9, ADR-0005 point 5, ADR-0007 (`key_id`), ADR-0009 (inbox and outbox growth),
  ADR-0016 (agent run steps), ADR-0021 (notifications), ADR-0023 (migration step), document 01 O-03, document 02
  §Security, epic E-45

## Context

Store isolation is enforced in the application: every repository method takes a `TrustedStoreScope` and filters by
`organization_id` and `store_id` (ADR-0004). ADR-0004 point 9 promised PostgreSQL row-level security as a second
line of defense before the pilot, so that a forgotten filter in a new query cannot return another store's rows.
Two more pilot prerequisites are open: integration credentials are sealed with one static key and cannot be
rotated (ADR-0007 kept a `key_id` column for this), and nothing removes data that has served its purpose: the
webhook inbox and outbox grow without limit, agent run steps keep prompt and response content forever and the
audit has no retention at all (ADR-0004 point 8).

Two facts shape the design. Superusers, roles with `BYPASSRLS` and table owners (unless the table is forced) bypass
RLS, so a runtime that connects as the owner of the tables has no second line of defense at all. And some work is
not tied to one store (the inbox processor before the store is known, sweeps, the outcome observer, retention,
metrics); it must keep working without a general way around the policies.

## Decision

1. **Two database roles with separate credentials.**
   - `operator_migrator` (LOGIN) owns the schema and every table, sequence and function. It is used only by the
     migration step of a deployment (ADR-0023) and never by the API or the worker. It is `NOSUPERUSER` and
     `NOBYPASSRLS`.
   - `operator_runtime` (LOGIN) is the only role the API and the worker connect as. It owns nothing and is
     `NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE`. It has `USAGE` on the schema and `SELECT, INSERT, UPDATE,
     DELETE` on the application tables (default privileges of `operator_migrator` cover future tables), no DDL and
     no `TRUNCATE`. It has `SELECT` only on `flyway_schema_history` (for the start-up version check) and `SELECT,
     INSERT` only on `audit_event` (ADR-0004 point 8).
   - Both roles are created out of band by the database administrator (the provider's console or
     `infra/db/bootstrap-roles.sql` locally, in Compose and in Testcontainers), so neither needs `CREATEROLE`.
     Migrations only grant and revoke; a migration that needs a role that does not exist fails.
2. **Policies, forced and fail-closed.** Every table with `store_id` and `organization_id` gets `ENABLE` and `FORCE
   ROW LEVEL SECURITY` (so not even the owner is exempt by accident) and one policy `store_isolation`: a row is
   visible and writable only when both columns equal `current_setting('app.store_id', true)` and
   `current_setting('app.organization_id', true)` cast to `uuid`. A missing or empty setting matches no row and
   lets no row be written; there is no "unset means everything" branch. `audit_event` has a read policy for the bound
   store and an insert policy that also accepts organization-level entries without a store, but only when
   `app.organization_id` is set and equal. Tables without a store (organizations, stores, members, users) are
   governed by grants and by the identity module's queries, as today. A schema test fails when a store table lacks
   `FORCE`, RLS or the policy, or when the runtime role owns an object, has `BYPASSRLS` or holds a privilege outside
   the list above.
3. **Scope management.** The scope reaches the database only from `StoreRowGuard` (identity), which is bound only
   where `StoreAccess` built a `TrustedStoreScope` for a user or a named system actor: the web scope resolver
   (cleared after the request by an interceptor), the Temporal activity interceptor (cleared in `finally`) and the
   system runner of point 4.
   - Binding while another scope is bound is refused (no silent switch); the guard is a stack of one.
   - `RowSecurityDataSource` sets `app.store_id` and `app.organization_id` on every connection checkout, and resets
     them to empty when the connection is returned. When a transaction is active the values are set
     transaction-local (`set_config(..., true)`) at its start, so they end with it even if the reset is skipped.
   - If setting or resetting fails, the connection is closed and evicted from the pool, never handed out.
   - There is no switch that turns RLS off in production: `operator.row-security.enabled=false` is accepted only in
     the `local` and test profiles, and `CloudConfigurationCheck` refuses it (ADR-0023).
   - Unbound connections stay possible (for point 4 and the identity tables), and on store tables they see and
     write nothing.
4. **System operations.** Work that is not started by a user follows three explicit rules. Every rule is in code
   that is reviewed with this ADR, not chosen at run time.
   - **Per-store work runs bound.** Sweeps, the outcome observer, the Meta poller, retention and the delivery retry
     list the active stores through `StoreAccess` and then, for each store, bind a `TrustedStoreScope` of the
     system actor of that job (`SYSTEM:<job>`) and do the work under RLS. A failure in one store does not stop the
     others. Writes are audited with that actor, as user writes are.
   - **Cross-store reads go through named functions.** When a question spans stores and returns no store data (the
     worker's gauges in ADR-0024, "which stores have due work", "which store owns this webhook endpoint"), it is a
     `SECURITY DEFINER` function owned by `operator_migrator` with a fixed `search_path`. It returns only counts or
     identifiers, and `EXECUTE` on it is granted to `operator_runtime`. The functions are listed in one migration
     file per module, and the schema test fails on a definer function that is not in the list.
   - **Privileged maintenance is a function too.** Deleting audit rows (point 6) runs only through
     `apply_audit_retention(cutoff, batch_size)`, a definer function that enforces the 365-day floor itself. The
     runtime role has no `DELETE` on `audit_event`.
   - Nothing at runtime connects as `operator_migrator`, and no runtime code path has a general bypass. Adding a
     new cross-store need means adding a function and revising this list.
5. **Credentials key ring.** `SecretCipher` seals with the current key (`operator.integration.secrets-key` and
   `secrets-key-id`) and opens with the current key or any key listed in
   `operator.integration.previous-secrets-keys` (`keyId → base64`). A credential sealed with a previous key is
   resealed with the current key the next time it is opened (`IntegrationConnections`, `MetaConnections`), so a
   rotation is: add the new key as current, move the old one to the previous list, remove it once no row uses it
   (a count by `key_id` is logged at start). Envelope encryption with a cloud KMS is chosen with the hosting in
   E-46: it replaces where the key bytes come from, not the format.
6. **Retention.** Each module that owns data declares a `RetentionTask` (identity interface) with a name and a
   period from `operator.retention.periods`. `RetentionRunner` (worker profile) runs them every
   `operator.retention.interval` in batches of `operator.retention.batch-size`, per store under the system actor
   `SYSTEM:retention` (point 4), and records one `RETENTION_APPLIED` audit entry per task and store with the count.
   The pilot defaults, to be confirmed with O-03:

   | Data | Period | Action |
   |---|---|---|
   | `webhook_inbox` rows no longer `RECEIVED` | 30 days | delete |
   | `outbox_event` rows already dispatched | 30 days | delete |
   | `agent_run_step.payload` (prompts, responses, tool results) | 90 days | replaced by `{"redacted": true}`; hashes, tokens and cost stay on `agent_run` |
   | `operator_notification` with its read marks and finished deliveries | 180 days | delete |
   | `audit_event` | 400 days | delete, through `apply_audit_retention` only |

   The runner is **off by default** (`operator.retention.enabled=false`) until the periods are approved.
   The audit trigger allows a `DELETE` only inside a transaction that set `operator.audit_retention = 'on'`, and
   only for rows older than 365 days, so neither a bug nor a short misconfigured period can remove recent audit.
   Decisions, evidence, snapshots, approvals, authorized actions, the execution ledger and outcomes are kept:
   they are the record of why an action happened.

## Alternatives considered

- **One NOLOGIN store role switched on per connection, owner not forced (the first draft of this ADR).** It needs
  one set of credentials, but the runtime keeps the owner's rights: any unbound connection, by design or by a
  forgotten binding, bypasses every policy. Rejected in review.
- **`FORCE ROW LEVEL SECURITY` with a permissive policy when unset.** Protects nothing for the work that matters:
  a forgotten binding would silently fall back to "everything visible".
- **A runtime role with `BYPASSRLS` for jobs.** Simple for the jobs, but it is the general bypass this ADR removes;
  per-store binding and named functions give the jobs what they need.
- **Only `SET LOCAL` per transaction.** Most `JdbcClient` reads run outside a transaction and would run without the
  settings; they would see nothing (fail closed) but would not work. Checkout setting plus reset, with
  transaction-local values inside transactions, covers both.
- **One retention job that knows every table.** Spreads knowledge of other modules' tables into one place; module
  tasks keep each table with its owner.

## Consequences

- Every environment needs two database credentials. The migration credentials reach only the migration step
  (ADR-0023), never the API or the worker.
- Every new store table needs `ENABLE` and `FORCE ROW LEVEL SECURITY` and a `store_isolation` policy in its
  migration; the schema test enforces it.
- Every cross-store need is a reviewed definer function; jobs that used to read all stores in one query now
  iterate stores. That costs a few more queries per run, which is acceptable at pilot scale.
- One extra statement per connection checkout and one per return.
- Roles are cluster-wide, so environments sharing a cluster must use different role names (configuration).

## Implementation status

The code on the pilot branch implements the first draft: migration V23 creates a `NOLOGIN` `operator_app` role
switched per connection, owners are not forced, unbound jobs run as the owner and bypass RLS, and Flyway runs inside
the application. After this revision is approved, a follow-up changes it. That follow-up:

- Adds the bootstrap script and the two roles, and has migrations grant to `operator_runtime`.
- Forces RLS and makes the policies fail-closed with `current_setting(..., true)`.
- Moves the jobs to per-store binding and the definer functions.
- Adds the reset and eviction in `RowSecurityDataSource` and the profile rule for `operator.row-security.enabled`.
- Moves migrations to the separate step of ADR-0023.

## Verification

- Schema test: `FORCE` RLS and a policy on every store table; the runtime role owns nothing, has no `BYPASSRLS`,
  holds only the listed privileges and no `DELETE` on the audit; every definer function is in the reviewed list.
- Isolation test, connected as `operator_runtime`:
  - With store A bound, an unfiltered query sees only A's rows and an insert for store B is refused.
  - Unbound, a query on a store table returns nothing and an insert is refused.
  - A failed reset evicts the connection, and the binding does not outlive a request, an activity or a system
    iteration.
  - Workflow tests run their activities under the binding.
- Job tests: each per-store job processes two stores under their own bindings; the definer functions return only
  counts or identifiers.
- The full test suite runs connected as `operator_runtime` with RLS on.
- Cipher tests: open with a previous key, reseal on open, unknown key refused.
- Retention tests: each task removes or redacts only expired rows; the audit function and trigger refuse recent
  rows and deletes outside the retention transaction.
