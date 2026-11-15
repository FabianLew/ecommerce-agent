# Runbooks

One section per alert in [`infra/observability/alerts.yml`](../../infra/observability/alerts.yml), then the routine
procedures. Every action that changes a store is taken in the application (so it is audited), never directly in the
database. The first step for anything that could change ads is always the same: if in doubt, **stop mutations** for
the store (Settings → Stop actions), then investigate.

Useful views: the dashboard `infra/observability/dashboard-pilot.json`, the Activity list of the store (type
Action), the Temporal UI for the namespace, and the JSON logs filtered by `correlationId` or `storeId`.

## Alerts

### Execution unknown

An ad pause was sent or may have been sent, and no read confirmed the result (`UNKNOWN`, ADR-0020).

1. Open the decision from the Activity list; the ExecutionTimeline shows the last code (`PAUSE_NOT_CONFIRMED`,
   `META_UNREACHABLE`, ...).
2. Check the ad in Meta Ads Manager. If it is paused, nothing else is needed: the next reconciliation records it as
   `EFFECT_FOUND_ON_RECONCILIATION` with authorship `UNCERTAIN`. If it is running, decide with the store owner
   whether to pause it by hand; the application never writes again on its own for an `UNKNOWN` row.
3. If Meta is unreachable for everyone, see *Connection failing*.

### Execution stuck

An execution stayed `IN_PROGRESS` or `APPLIED` for 15 minutes: the worker died during the attempt or the
verification activity is not running.

1. Check that a worker is up (*Worker missing*) and that the episode workflow is still open in Temporal.
2. A restarted worker resumes the workflow; the executor starts from the ledger and reconciles by reading (IG-19).
   Do not terminate the workflow unless the store owner has confirmed the ad state; terminating leaves the row as
   it is.

### Mutations stopped

A store has been stopped for a day. Confirm with the person who stopped it (audit `MUTATIONS_STOPPED`, reason) that
it is still intended. Resuming needs `STORE_MANAGE` and the version shown in Settings.

### Observations overdue

Outcome checkpoints (`H24`, `D7`) are past due by an hour: the outcome observer is not running
(`operator.outcomes.observe-interval`, worker profile). Check the worker logs for `OutcomeObserver`; nothing is
lost, the next run catches up.

### Webhook backlog

WooCommerce deliveries wait more than 10 minutes, so stock and orders may be stale and analyses late.

1. Check the worker and API logs for `WebhookInboxProcessor` errors and the database for long locks.
2. If processing is fine but slow, scale the worker. Polling reconciliation (ADR-0010) still runs, so data catches
   up even if webhooks are lost.

### Outbox backlog

Outbox events are not dispatched: inventory changes do not start analyses. Check `OutboxDispatcher` in the worker
logs and Temporal reachability (a start that fails is retried with backoff).

### Connection failing

A WooCommerce or Meta connection is in `ERROR`. The store's Integrations page shows the last error code.

- `AUTH_FAILED` or `PERMISSION_DENIED`: the store owner renews the WooCommerce key or the Meta token (or grants
  `ads_management`); the next check moves it back.
- `UNREACHABLE` or `TLS_ERROR` for every store: check egress from the environment and the provider's status page.
- While Meta is in error, no pause can be authorized (`META_NOT_CONNECTED`); proposals stay for approval.

### Notification deliveries exhausted

E-mail deliveries reached `EXHAUSTED` (ADR-0021): refused for good, out of attempts, or uncertain and never
reconciled. The notification is still in the app, so nobody misses it there, but approvers may not have been told.

1. Check the mail provider's status and credentials (O-11). With `LoggingMailProvider` nothing is sent at all.
   `error_code` says why: a provider code for a refusal, `NOT_RECONCILABLE` or `LOOKUP_UNKNOWN` for an uncertain
   dispatch the provider could not confirm. Do not resend an uncertain one by hand without checking the provider's
   log for its `Message-ID`: it may have arrived.
2. After the provider is fixed, new notifications are delivered; exhausted ones are not retried. Tell the approvers
   of the affected stores to open the Notifications page.

### Agent failing

More than half of the agent runs in an hour did not succeed. The deterministic baseline keeps proposing, so the
product works without the agent (ADR-0016).

1. Look at the run statuses on the dashboard: `MODEL_FAILED` (provider outage or key), `LIMIT_EXCEEDED` (limits too
   low for the case), `OUTPUT_INVALID` or `OUTPUT_REJECTED` (prompt or schema regression).
2. For a provider outage nothing is needed. For a regression, switch the agent off
   (`operator.agent.enabled=false`) and redeploy; keep the failing runs for the quality report.

### Agent cost high

The estimated model cost passed 20 USD in a day. Check whether the number of analyses grew (a webhook storm causes
many) or the runs grew (more model calls per run). Reduce the run limits or switch the agent off; the price version
of every call is on its reservation (`ai_budget_reservation.price_id`, catalog `ai_price`).

### AI budget threshold reached

An AI budget (platform, organization, store or automation; monthly or daily) reached a threshold of its limit: by
default 70, 90 and 100 percent (parameter set `operator.ai-budget-alerts`, a change needs a new version
`ai-budget-alerts/N`). Each threshold is recorded once per budget period in `ai_budget_alert`; usage is reserved
plus spent. Amounts are in the budget's currency (USD) and are never converted.

1. See which budget: `GET /api/v1/platform/ai/budget-alerts` (platform operators), or the log line
   `AI budget ... reached`.
2. At 70 or 90 percent: check whether the growth is expected (more stores, more analyses) or a runaway (see "Agent
   cost high"). Nothing stops on its own before the limit.
3. At 100 percent new calls of that budget are already refused (fail closed) and the baseline proposes. Raise the
   limit with a new budget policy version, or stop AI for the store or automation if the spending is not wanted.
   No stop is switched on automatically.

### AI reservation unknown

An AI call was sent and its result is not known (timeout, dropped connection, worker crash after sending), so it may
have been charged. Its reservation keeps counting against every budget (ADR-0027 section 4.5); it is never retried
automatically.

1. Find it, as a platform operator: `GET /api/v1/platform/ai/reservations/uncertain` lists every store's uncertain
   calls, oldest first, with the provider's request id when the provider gave one.
2. Check the provider's usage or console for that request id, or for the request around `sentAt`.
3. Close it with `POST /api/v1/platform/ai/reservations/{id}/reconciliation`: `NOT_CHARGED` when the provider shows
   no charge, `CHARGED` with the confirmed `cost`, or `CONSERVATIVE`; give the evidence as `reason`. Or leave it:
   after `operator.ai-governance.unknown-conservative-settlement-after` (24 hours) the sweep records the full
   reserved amount as spent (`RECONCILED_CONSERVATIVE`).
4. When the provider's data arrives after a conservative close, correct it with `POST .../{id}/correction`
   (`NOT_CHARGED` or `CHARGED` with the amount). The budgets move by the difference; one correction per call.
5. Many at once point to a provider outage or a network problem between the worker and the provider.

### AI reservations overdue

Reservations stay `RESERVED` or `IN_PROGRESS` more than 10 minutes past their expiry, so the worker's reservation
sweep is not running. Budgets are not overspent (the reservations still count), but they stay blocked. Check that the
worker runs (see "Worker missing") and look for `AI reservations swept` and errors of `ai_sweep_reservations` in its
logs.

### AI cost overrun

A call cost more than its conservative reservation. The full cost is recorded (`ai_usage_event.overrun_amount`) and
budgets already count it. Look at the event: usage far above `max_output_tokens` or unexpected cache or extra units
mean the estimate is wrong for that automation (margin, token limits, or a feature without a price). Fix the estimate
before the next price or limit change; repeated overruns are a reason to switch the agent off.

### AI stopped

The platform AI stop is on (ADR-0027 section 12): no new agent run is admitted and no reserved call is sent, on
every instance, without a restart. Calls already sent finish and are settled. The deterministic baseline keeps
proposing and store monitoring continues; the stop of business mutations (ADR-0020) is separate.

- Stop (provider incident, runaway cost, suspected abuse), with the administration role:
  `INSERT INTO ai_platform_stop_change (stopped, reason, changed_by) VALUES (true, '<why>', 'ops:<name>')`.
- Resume the same way with `stopped = false` once the cause is fixed. The table is append-only: every decision stays
  in the history, and the newest row is the current state.
- The alert repeats while the stop is on, so a forgotten stop is noticed.

### AI stopped for a store or an automation

A platform operator stopped AI for one store, for one automation on every store, or for one automation on one store
(ADR-0027 section 12), and it has been on for a day. Like the platform stop it refuses new runs and every reserved
call not yet sent, and the baseline keeps proposing. The stops are independent: resuming one, or the platform stop,
never resumes another, and none is switched on automatically.

- See what is stopped and why: `GET /api/v1/platform/ai/stops` (history: `/stops/history`).
- Stop or resume, as a platform operator: `POST /api/v1/platform/ai/stops` with `storeId`, `automationType` (either or
  both) and `stopped`, `reason`. Every decision is kept and audited (`AI_STOPPED_SCOPED`, `AI_RESUMED_SCOPED`).
- Confirm with the person who stopped it that it is still intended.

### AI admission denied

Agent runs were not admitted because the store or the platform reached its limit of active runs
(`STORE_LIMIT`, `GLOBAL_LIMIT` on `operator_ai_admissions_total`, tagged with the automation's `priority`). A run
refused a slot before anything was sent (also by a provider limit) uses no model attempt: its episode waits on a
Temporal timer and asks the same model again, at most `operator.ai-admission.max-wait` (2 minutes) in total
(`operator_ai_admission_waits_total`, outcome `WAITING`). Only when the wait is used up (outcome `GAVE_UP`, alert
`AiAdmissionWaitExhausted`) does the analysis fall back to the baseline. A few are normal during a burst. Many point
to runs that do not end (check "Agent failing") or to limits that are too low: the store limit is
`max-concurrent-runs-per-store` of the agent's run policy (a new policy version), the platform limit
`operator.ai-governance.max-concurrent-runs`. A `STANDARD` automation fills at most
`operator.ai-admission.standard-capacity-percent` (75) of the platform limit and a `BACKGROUND` one
`background-capacity-percent` (50); the rest is kept for `CRITICAL` work (the Inventory Guardian by default). If
critical work itself hits `GLOBAL_LIMIT`, raise the platform limit; to change priorities or shares, set a new
version of the parameter set (`ai-admission/N`). A lease of a lost worker expires one minute after the run's wall
time.

### AI provider throttled

The provider answered with rate limiting (`operator_ai_provider_throttled_total`): the model is paused for the
provider's retry-after (at most `operator.ai-provider-limits.max-pause`) and its calls end `LIMIT_EXCEEDED` with
`AI_GOVERNANCE:PROVIDER_LIMIT:PROVIDER_PAUSED` until then, so the baseline proposes. Or many calls were refused by the
platform's own provider limits (`operator_ai_provider_slots_total` by outcome: `REQUEST_LIMIT`, `INPUT_TOKEN_LIMIT`,
`OUTPUT_TOKEN_LIMIT`, `PARALLEL_LIMIT`). Check the provider's console for the account's limits, then lower or raise
`operator.ai-provider-limits` with a new version (`ai-provider-limits/N`). The current pauses are in
`ai_provider_cooldown` (read with the administration role).

### API rate limited

Many requests that start work (sync jobs, connection checks, monitoring start, approvals) got `429 RATE_LIMITED`
(`operator_api_rate_limited_total` by endpoint). A few are normal when someone clicks repeatedly. Many from one
endpoint point to a script or a client retrying without backoff; many for webhooks point to a delivery storm from
the shop (the inbox still drops duplicates). The limits per user, store, endpoint and webhook key are the parameter
set `operator.api-rate-limits` (a change needs a new version, `api-rate-limits/N`). The mutation stop is never
limited. A repeated command with the same `Idempotency-Key` is answered from the first answer
(`operator_api_idempotent_replays_total`), not run again.

### API errors

More than 2% of API requests fail. Find the endpoint in the logs (`http.server.requests` with status 5xx) and the
first exception. A failing database or identity provider shows on `/actuator/health`.

### Worker missing

No worker publishes metrics: workflows, sweeps, observers and retention are not running. Start or restart the
worker (`SPRING_PROFILES_ACTIVE=cloud,worker`). Workflows continue from their history after the restart (IG-24).

## Procedures

### Restore drill (monthly, and after each schema change in staging)

1. Take the newest dump from the backup bucket (or run `infra/backup/backup.sh` against staging).
2. Run `infra/backup/restore-check.sh <dump>` against a scratch server, never against the live database.
3. Record the date, the dump and the output in the operations log. A failure is a blocker for the next release.

### Rotating the credentials key (ADR-0022 point 5)

1. Generate a new key: `openssl rand -base64 32`; store it in the secret store.
2. Deploy with the new key as `INTEGRATION_SECRETS_KEY` and a new `INTEGRATION_SECRETS_KEY_ID`, and the old key
   under `operator.integration.previous-secrets-keys.<old id>`.
3. Values are resealed the next time they are used. The start-up log lists how many credentials still use an old
   key; when it shows none, remove the old key and deploy again.

### Bootstrapping the database roles (ADR-0022 point 1)

Once per environment, before the first migration, as the RDS master user (the `db-admin` task of
`infra/terraform`, README step 2):

1. Generate the passwords of `operator_migrator`, `operator_runtime` and `keycloak` and put them in Secrets Manager.
2. On RDS the master user is not a superuser: start the session with `SET createrole_self_grant = 'set'`, so that
   it may hand the database and the schema to `operator_migrator`.
3. Run `infra/postgres/init/02-operator-roles.sql` with those passwords in place of the local ones (`psql -v
   ON_ERROR_STOP=1`), then `CREATE ROLE keycloak LOGIN PASSWORD '…'` and `CREATE DATABASE keycloak OWNER keycloak`.
4. Run the migration task; `SchemaVersionCheck` lets the services start only on the current schema.

The master user is used for nothing else. Its password is rotated by RDS.

### Enabling retention (ADR-0022 point 6)

Once the periods are approved (O-03), set `operator.retention.enabled=true` for the worker, in staging first. Each
run records `RETENTION_APPLIED` audit entries with the number of rows per task, and `RETENTION_SKIPPED_HOLD` for a
store under a legal hold.

### Granting a platform operator (ADR-0027 section 15)

Platform operators reconcile uncertain AI calls and, later, switch AI off for a store or an automation. The person
signs in once so the application knows them, then, with the administration role:
`INSERT INTO platform_operator (user_id, granted_by, reason) SELECT id, 'ops:<name>', '<why>' FROM app_user WHERE
email = '<email>'`. To revoke: `UPDATE platform_operator SET revoked_at = now() WHERE user_id = '<id>'`. Store roles
never grant it, and the application cannot change the list.

### Legal hold (ADR-0022 point 7)

A member with `STORE_MANAGE` sets a hold with a reason (`PUT /api/v1/stores/{id}/retention-hold`); retention then
skips the whole store until the hold is released (`DELETE`). Both are audited. A hold does not stop the backups'
own lifetime: a dump older than 30 days is still deleted, so export what the hold needs first.

### Responding to a wrong pause

1. Stop mutations for the store, and lower its `PAUSE_AD` permission to `PROPOSE_ONLY` (Settings → Actions,
   ADR-0025).
2. Resume the ad in Meta Ads Manager (the application never resumes ads in the pilot).
3. Record what happened in the decision (rejection reason on the next proposal) and in the pilot log; the
   outcome record keeps the pre-state and post-state for the review.
