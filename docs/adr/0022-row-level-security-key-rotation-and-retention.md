# ADR-0022: Row-level security, key rotation and data retention

- Status: Proposed
- Date: 2026-11-15
- Revised: 2026-11-15 (review: separate migration and runtime roles, fail-closed scope, system operations; O-03
  retention decisions: periods from the end of processing, exceptions, holds, backups)
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
   - `operator_system` (NOLOGIN) owns the reviewed definer functions of point 4 and nothing else. The migrator may
     set it as an owner (`SET TRUE`) without inheriting its rights (`INHERIT FALSE`).
   - The roles are created out of band by the database administrator with
     `infra/postgres/init/02-operator-roles.sql` (Compose and Testcontainers run it on a new database; staging and
     production run it with passwords from the secret store), so none of them needs `CREATEROLE`. Migrations only
     grant and revoke, and V25 fails when the roles are missing. The NOLOGIN `operator_app` of the first draft is
     still created so that V23 applies unchanged; V25 removes all its privileges.
2. **Policies, forced and fail-closed.** Every table with `store_id` and `organization_id` gets `ENABLE` and `FORCE
   ROW LEVEL SECURITY` (so not even the owner is exempt by accident) and one policy `store_isolation`: a row is
   visible and writable only when both columns equal `current_setting('app.store_id', true)` and
   `current_setting('app.organization_id', true)` cast to `uuid`. A missing or empty setting matches no row and
   lets no row be written; there is no "unset means everything" branch. `audit_event` has a read policy for the bound
   store and an insert policy that also accepts organization-level entries without a store, but only when
   `app.organization_id` is set and equal. Tables without a store (organizations, stores, members, users) are
   governed by grants and by the identity module's queries, as today; `store_membership` is one of them, because
   `StoreAccess` reads it to build a scope before any store can be bound. A schema test fails when a store table lacks
   `FORCE`, RLS or the policy, or when the runtime role owns an object, has `BYPASSRLS` or holds a privilege outside
   the list above.
3. **Scope management.** The scope reaches the database only from `StoreRowGuard` (identity). It is bound only
   from a `TrustedStoreScope`, which only `StoreAccess` builds, in four places:
   - the web scope resolver, cleared after the request by an interceptor;
   - the Temporal activity interceptor, cleared in `finally`;
   - the system runner of point 4;
   - `StoreScopeBinding`, an advisor on every application bean method that receives a `TrustedStoreScope`. It
     binds that scope for the call, before the transaction advice, so the queries a method runs are checked against
     the same store its code filters by.

   The rules for the binding and the connections:
   - Binding another store while one is bound is refused (no silent switch). Binding the same store again is a
     no-op.
   - `RowSecurityDataSource` sets `app.store_id` and `app.organization_id` on every connection checkout. It sets
     them again before a statement whenever the thread's binding differs from what the connection holds (a
     transaction that started unbound and then calls a store-scoped method, or settings undone by a rollback).
     It resets them to empty when the connection is returned.
   - If setting or resetting fails, the connection is evicted from the pool, never handed out.
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
   - **Cross-store reads go through named functions.** When a question spans stores and returns no store data, it
     is a `SECURITY DEFINER` function owned by `operator_system` with a fixed `search_path`.
     - It returns only counts or identifiers, and `EXECUTE` on it is granted to `operator_runtime` only.
     - `operator_system` reads the tables a function needs through a read policy for that role, with column grants
       where the function needs only a few columns.
     - The reviewed list (V25):
       - `webhook_endpoint(key)`: which store owns a webhook endpoint, before the delivery is resolved;
       - `operational_metric(name)`: the worker's gauges in ADR-0024;
       - `apply_audit_retention(...)`: see below.
       - `ai_reserve(...)` and `ai_release(...)` (V30, ADR-0027): the atomic budget reservation and its release.
         They take the store only from the session settings, fail closed when none is bound, and are the only way
         the runtime reaches platform and organization budgets.
       - `ai_mark_sent`, `ai_settle`, `ai_mark_unknown`, `ai_reconcile` (V31, ADR-0027): the rest of a
         reservation's life, on the bound store only; `ai_sweep_reservations` closes expired reservations of all
         stores and returns only counts.
     - Only these definer functions are executable by `operator_runtime`, and no function of `operator_system` is
       executable by `PUBLIC` (V31). Until V31 the runtime reached them through `PUBLIC`: the per-schema default of
       V25 cannot remove the global `PUBLIC` default, and the migrator's `GRANT`/`REVOKE` after the owner change had
       no effect. V31 sets the grants as the owner and the schema test checks both rules.
     - The schema test fails on any other definer function.
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
   E-46: it replaces where the key bytes come from, not the format. On the VPS of ADR-0028 there is no KMS: the key
   comes from the SOPS/age secret file (ADR-0028 point 4) and is rotated the same way.
6. **Retention.** Each module that owns data declares a `RetentionTask` (identity interface) with a name and a
   period from `operator.retention.periods`. `RetentionRunner` (worker profile) runs them every
   `operator.retention.interval` in batches of `operator.retention.batch-size`, per store under the system actor
   `SYSTEM:retention` (point 4), and records one `RETENTION_APPLIED` audit entry per task and store with the count.
   The periods approved by the project owner on 2026-11-15 (O-03). Each period runs from the end of processing,
   never from arrival:

   | Data | Period, from | Action |
   |---|---|---|
   | `webhook_inbox` rows `PROCESSED` or `IGNORED` | 30 days from `processed_at` | delete |
   | `outbox_event` rows already dispatched | 30 days from `dispatched_at` | delete |
   | `agent_run_step.payload` (prompts, responses, tool results) | 90 days from the step | replaced by `{"redacted": true}` |
   | `operator_notification` with its read marks, deliveries and provider events | 180 days from creation, once no delivery is open | delete |
   | `audit_event` | 400 days | delete, through `apply_audit_retention` only |

   The runner is **off by default** (`operator.retention.enabled=false`). It is switched on in staging first, then
   in production after the GDPR review of point 7.
   The audit trigger allows a `DELETE` only inside a transaction that set `operator.audit_retention = 'on'`, and
   only for rows older than 365 days, so neither a bug nor a short misconfigured period can remove recent audit.
7. **What retention never removes, and why.**
   - *Active processes and data still to reconcile* are excluded by every task, whatever their age: an inbox item
     still `RECEIVED` or `FAILED` (a failed item stays for diagnosis until the store's data was reconciled and an
     operator closes it), an outbox event not dispatched, a notification with a delivery `PENDING`, `SENDING`,
     `FAILED` or `UNKNOWN` (ADR-0021), and anything of an episode that is not `CLOSED` or `RESOLVED`.
   - *LLM content is separate from decision metadata.* The raw model exchange (prompts, responses, tool results in
     `agent_run_step.payload`) is redacted after 90 days. The run's metadata stays: model, versions, hashes of the
     prompt, knowledge and tools, token counts, cost, stop reason and step kinds. The decision record stays too:
     proposals with their rationale, evidence, snapshots, policy evaluations, approvals, authorized actions, the
     execution ledger and outcomes. It is the record of why an action happened, and it holds no raw prompt.
   - *Legal hold.* An operator can put a store on hold (`store_retention_hold`, with a reason and who set it, audited
     as `RETENTION_HOLD_SET` and `RETENTION_HOLD_RELEASED`). While a hold exists no task runs for that store, and the
     runner records `RETENTION_SKIPPED_HOLD` instead of the counts. A hold has no end date; it is released
     explicitly.
   - *Minimisation.* Notifications store a kind and structured parameters, never rendered text or e-mail bodies.
     E-mails are rendered at sending and not stored. Snapshots and prompts hold aggregates of orders (units,
     revenue per variant), never a customer's name, address or e-mail. Logs never contain message bodies, recipients'
     addresses or tokens.
   - *Backups.* Deleted rows remain in the backups until those expire (ADR-0023 point 7): point-in-time recovery
     keeps 14 days, the daily logical dumps 30 days, and a snapshot taken by hand is deleted within 30 days. So a
     deleted row is gone from every copy at most 30 days after its deletion. A restored database runs the retention
     tasks before it is used again, so data past its period does not return. This is stated in the privacy
     information for the pilot store.
   - *GDPR review before real data.* Before the pilot store's data is imported: the data processing agreement with
     the store, the records of processing, the list of subprocessors (the hosting provider: Hetzner per ADR-0028,
     Temporal Cloud, the model provider of ADR-0016 and, when used, AWS for Bedrock or SES), the transfer basis for each, and whether a DPIA is needed. Until then only test stores and synthetic
     data are processed.

## Alternatives considered

- **One NOLOGIN store role switched on per connection, owner not forced (the first draft of this ADR).** It needs
  one set of credentials, but the runtime keeps the owner's rights: any unbound connection, by design or by a
  forgotten binding, bypasses every policy. Rejected in review.
- **`FORCE ROW LEVEL SECURITY` with a permissive policy when unset.** Protects nothing for the work that matters:
  a forgotten binding would silently fall back to "everything visible".
- **A runtime role with `BYPASSRLS` for jobs.** Simple for the jobs, but it is the general bypass this ADR removes;
  per-store binding and named functions give the jobs what they need.
- **Only `SET LOCAL` per transaction.** Most `JdbcClient` reads run outside a transaction and would run without the
  settings; they would see nothing (fail closed) but would not work. Setting the scope on checkout and before a
  statement when it changed, and resetting it on return, covers both.
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
- Roles are cluster-wide. The role names are fixed, so each environment has its own database instance (ADR-0023).
- A future data migration that must touch store rows adds a policy for the migrator in that migration and drops it
  at the end, because the migrator is subject to the forced policies too.

## Implementation status

Implemented on the pilot branch with migration V25 (V23 and V24 unchanged):
- the role bootstrap script and the three roles;
- forced, fail-closed policies for the runtime role;
- `StoreScopeBinding` and the statement-level scope in `RowSecurityDataSource`;
- `SystemStoreRunner`, with the inbox, outbox, sync reconciliation and schedules, Meta polling, notification retry,
  outcome observation and retention moved to per-store runs;
- the three definer functions;
- the migration step of ADR-0023.

The full test suite connects as `operator_runtime`, and test fixtures write as the database superuser.

## Verification

- Schema test: `FORCE` RLS and a policy on every store table; the runtime role owns nothing, has no `BYPASSRLS`,
  holds only the listed privileges and no `DELETE` on the audit; every definer function is in the reviewed list.
- Isolation test, connected as `operator_runtime`:
  - With store A bound, an unfiltered query sees only A's rows and an insert for store B is refused.
  - Unbound, a query on a store table returns nothing and an insert is refused.
  - A failed reset evicts the connection, and the binding does not outlive a request, an activity or a system
    iteration.
  - Workflow tests run their activities under the binding.
- Job tests: the system runner processes two stores under their own bindings; the definer functions are exactly
  the reviewed list, owned by `operator_system`, which cannot log in or create objects.
- The full test suite runs connected as `operator_runtime` with RLS on.
- Cipher tests: open with a previous key, reseal on open, unknown key refused.
- Retention tests: each task removes or redacts only expired rows; the audit function and trigger refuse recent
  rows and deletes outside the retention transaction.
