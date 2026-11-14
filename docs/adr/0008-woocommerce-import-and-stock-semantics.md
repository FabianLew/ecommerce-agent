# ADR-0008: WooCommerce import (14 days) and stock semantics

- Status: Accepted (owner acceptance, 2026-11-14)
- Date: 2026-11-14
- Related: document 03 (Inventory Guardian, IG-02, IG-25), ADR-0004, ADR-0007, backlog B-13, B-13b

## Context

The first Inventory Guardian needs the catalog, current stock levels and orders from at least 7 full local
days. Correction after the plan was approved: we do not block it on the full history, so B-13 imports a minimal window,
and B-13b will fetch the full history (12 months). Two decisions are hard to reverse: how we interpret
`stock_quantity` from WooCommerce (available stock and the risk of subtracting reservations twice depend on it, IG-02)
and when we consider a period of orders complete (whether the metrics are computed at all depends on it).

## Decision

1. **Stock semantics: `ALREADY_ACCOUNTED_FOR`.** Experiment on a local WooCommerce 11.2.1 (a product with
   `manage_stock`, stock 49, wp-cli):

   | Event | `stock_quantity` |
   | --- | --- |
   | `pending` order (2 pcs) | 49 (unchanged) |
   | → `on-hold` | 47 |
   | → `processing`, → `completed` | 47 |
   | → `cancelled` | 49 (returned to stock) |
   | new order straight in `processing` (3 pcs) | 46 |
   | → `refunded` | 46 (no automatic return) |
   | → `failed` | 49 (returned to stock) |

   WooCommerce stock already accounts for `on-hold`, `processing` and `completed` orders. Cart reservations from
   checkout (`wc_reserved_stock`) are not in the REST API, so `reserved_quantity` stays empty. Available stock =
   `stock_quantity`, without subtracting anything (IG-02).
2. **Stock tracking.** A simple product is its own only variant. A variation with `manage_stock=true` →
   `VARIANT`, `manage_stock="parent"` → `PARENT` (quantity from the parent product), `false` → `NONE` (no quantity,
   status only). A variable product is not a selling unit itself.
3. **Import window.** From the start of the store's local day 14 days ago until the moment the import starts. Query:
   `orders?after=…&before=…&dates_are_gmt=true&orderby=date&order=asc&status=any`, pages of 100.
4. **Completeness.** A period goes into `order_sync_coverage` only when the number of fetched orders equals
   `X-WP-Total` from the first page and all pages succeeded. Otherwise the run ends as
   `INCOMPLETE`, and metrics for that period return missing data instead of understated sales.
5. **Data minimization.** Orders are fetched with `_fields` limited to status, dates, amounts, currency and
   line items. Customer data (addresses, e-mail, phone) does not leave WooCommerce.
6. **Execution.** The Temporal workflow `WooImportWorkflow` (module `automation`) with catalog and order
   activities. It is started by a user command with `STORE_MANAGE` (`POST B/integrations/{id}/sync-jobs`), and it runs
   as the system actor `woo-import` with a `STORE_READ` scope: the import writes the store's source data and changes
   nothing in the store or in policies. Who started the import is recorded in the audit and in `sync_run`. One active
   import per store (unique index and workflow identifier).

## Alternatives considered

- **Subtracting `processing` orders from stock.** Gives double subtraction (IG-02) according to the experiment.
  Rejected.
- **Considering a period complete without comparing the count.** Faster, but orders added or removed during
  pagination would understate the metrics without a trace. Rejected.
- **Import inside the HTTP request.** No retries and no progress, risk of a timeout for larger stores. Rejected.

## Consequences

- Refunds (`refunded`) do not restore stock automatically; sales metrics (B-17) count only qualifying statuses,
  so a refunded order does not inflate sales, but stock may be lower than the physical stock.
- An order changed during the import may arrive with a newer state in the next run (upsert by
  `date_modified_gmt`). Incremental polling (B-14) closes this without another full window.
- The experiment concerns WooCommerce 11.2; plugins that change reservations may behave differently. The pilot checks
  this on a real store before recommendations are enabled.

## Verification

- The experiment from the table above (wp-cli on the local store from `infra/docker-compose.yml`).
- `WooCommerceImportTest`: pagination, variants and `manage_stock=parent`, field minimization, no coverage
  with a mismatched count, idempotent re-import, store isolation.
- `WooImportWorkflowTest`: activity order, marking failure and incompleteness.
- Manually: importing the local store through the UI; product, variant and order counts match WooCommerce.

## Addendum: durable workflow start and recovery (FIX-02, 2026-11-14, accepted)

The audit showed that a `sync_run` in the `RUNNING` state was created before `WorkflowClient.start`, and a start error
marked the run as `FAILED`. A process crash between the write and the start blocked the store permanently (unique
index), and a lost response after a successful start released the index although the workflow was running, so a second
import could start. The workflow execution timeout (2 h) ended it without the `finish()` step.

1. **The record as a durable request.** A `sync_run` in the `RUNNING` state with an empty `workflow_started_at` is a
   start request. The request tries to start the workflow right away and confirms the start; a start error does not
   change the state of the run (it is unknown whether the workflow started).
2. **Deterministic ID without reuse.** `woo-import-{runId}` with `REJECT_DUPLICATE`: starting the same run again
   (retry, reconciler) never creates a second execution.
3. **Reconciler in the worker** (every `operator.sync.reconcile-interval`, default 1 min): an unconfirmed run
   younger than `start-deadline` (15 min) is started and confirmed; an older one is confirmed if Temporal knows the
   workflow, otherwise it is closed as `FAILED/START_FAILED`. A confirmed run whose workflow ended without an outcome
   is closed as `FAILED` with the code `WORKFLOW_TIMED_OUT`, `WORKFLOW_CANCELED`, `WORKFLOW_TERMINATED`,
   `WORKFLOW_FAILED`, `WORKFLOW_NOT_FOUND` or `WORKFLOW_ENDED_WITHOUT_OUTCOME`. The reconciler never marks
   success.
4. **No locks.** All steps are idempotent (start by ID, confirming only an empty field, closing only a
   `RUNNING` row), so parallel runs in several workers are safe. Import steps refuse to work for a run that is not
   `RUNNING` (`RUN_CLOSED`), so a late start does not import.
5. **Scope.** The reconciler reads only identifiers from `sync_run` across all stores; the scope of the system actor
   `woo-import` is built by `StoreAccess.systemScope` from the organization and store stored in the run.

Rejected: a general outbox/dispatcher for starting the import. It is created in B-14/B-15 (ADR-0009); here the run
record is already the only request, so a separate table would duplicate state. After B-15 the import start may move
to the outbox.

Verification: `SyncRecoveryTest` (crash before the start, unavailable Temporal, lost response, timeout,
cancellation and termination, reconciliation during an import, an expired start and a late workflow, parallel
reconciliation).
