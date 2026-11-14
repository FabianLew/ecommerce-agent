# ADR-0010: Event dispatch to Temporal, WooCommerce polling and reconciliation, Integration Health

- Status: Accepted (2026-11-14)
- Date: 2026-11-14
- Related: document 01 §"Data flow", document 03 §3 and §11, document 04 §10, ADR-0004, ADR-0008,
  ADR-0009, backlog B-15, B-16

## Context

B-14 writes domain events to `outbox_event`. B-15 has to forward them to Temporal so that a redelivery does not
create a second workflow. B-16 has to complement webhooks with polling and reconciliation, maintain watermarks and
show integration health, and reconciliation has to detect a missed webhook. Hard to reverse are: the workflow
identifiers (deduplication and the future Inventory Guardian episode depend on them), what we consider an
authoritative stock read, the watermark semantics and when an integration blocks analysis as stale.

## Decision

1. **Dispatcher (B-15).** `OutboxDispatcher` in the `worker` profile locks a batch of undispatched events every 1 s
   (`FOR UPDATE SKIP LOCKED`, 100), runs `signalWithStart` for each and sets `dispatched_at` in the same
   transaction. A Temporal error increments `attempts` and postpones the event (`next_attempt_at`, up to 1 h). When
   the signal went through but the commit did not, the event is sent again: the workflow rejects a known `eventId`.
2. **Deterministic identifiers.**
   - `inventory-{storeId}-{variantId}`: `VariantInventoryWorkflow`, long-running, one per variant. It receives
     `inventory.changed`, `inventory.verification-requested` and `order.changed` (one signal per variant in the
     list, `eventId:variantId`). The Inventory Guardian episode starts from here in I7.
   - `store-sync-{connectionId}`: `StoreSyncWorkflow`, catalog verification requests (`product`).
   `signalWithStart` on a running workflow only adds a signal, so a repeated event does not create a second
   workflow. The workflow remembers the last 500 event identifiers and carries them through `continueAsNew` (every
   500 signals), so the history stays small.
3. **Authoritative variant read.** After a verification request, and also after `order.changed` if no newer
   variant stock arrived within 60 s, `VariantInventoryWorkflow` reads the variant (or the parent product for
   `PARENT`) directly from Woo and writes a `POLL` observation. A fresh read is the truth at the moment of reading,
   so we store it even within the same second as the last write (it resolves `AMBIGUOUS`). We skip only identical
   stock. Several requests within a 60 s window give one read.
4. **Polling (B-16).** The Temporal Schedule `woo-poll-{connectionId}` runs `IncrementalSyncWorkflow` (kind `POLL`)
   every 5 min: `products?modified_after={watermark − 2 min}` and the variations of changed variable products. Changes
   are stored like an authoritative read. The `PRODUCTS` watermark (the largest `date_modified_gmt`) moves only after
   all pages succeeded.
5. **Reconciliation (B-16).** The Schedule `woo-reconcile-{connectionId}` every hour: all orders
   `modified_after={ORDERS watermark − 2 min}`, without an upper bound, with an `X-WP-Total` comparison as in
   ADR-0008. An order placed before the run started has a modification date no earlier than its placement, so a
   complete read covers all orders placed up to the start. An upper bound (`modified_before`) would leave a gap:
   an order placed before the start and changed during the run would fall out of the read. When the count matches,
   we extend `order_sync_coverage` to the start of the run and move the watermark there. When it does not, nothing
   moves, and health shows a growing completeness delay. A nightly review of the whole catalog (full stock) is left
   for later.
6. **Missed webhook.** Polling compares every change with the latest observation. If the connection has a stored
   webhook secret, and a change from Woo is newer than our state and older than 2 minutes, we count it as a missed
   webhook. The number is stored with the run (`sync_check.missed_webhooks`). Orders from reconciliation that were
   not in the database or were in an older state count the same way. A change from before the secret was saved does
   not count.
7. **Integration Health** (the `health` field in `GET /stores/{storeId}/integrations`), computed on read:
   - `NOT_READY`: no successful initial import;
   - `STALE`: polling without success for 15 min or order completeness older than 2 h. Blocks analysis (03 §11);
   - `DEGRADED`: missed webhooks in the last 24 h, no webhook secret, `FAILED` entries in the inbox or
     an outbox backlog older than 5 min;
   - `HEALTHY`: all other cases.
   Every reason has a code and a fix step in the UI.
8. **Schedules.** At startup and every minute the worker creates (idempotently) the missing Schedules for
   `CONNECTED` connections with a successful import, so a store gets polling at most a minute after the import. The
   store context (actor `woo-sync`) is stored in the Schedule action header, and the first run starts right away.
   Overlap policy: `SKIP`, catch-up window 10 min. The intervals are pilot parameters
   (`operator.integration.woocommerce.poll-interval`, `reconcile-interval`).
9. **Actor.** The workflows run as the system actor `woo-sync` with a `STORE_READ` scope (ADR-0004). The scope
   comes from the start headers, and the activity interceptor verifies it against the database.

## Alternatives considered

- **One workflow per store for all events.** Fewer workflows, but a bottleneck and a long history.
  The risk detector works per variant (03 §3). Rejected.
- **A workflow per event (ID = eventId).** Easy deduplication, but no place for the variant state and
  debounce. Rejected.
- **Polling without `modified_after`, the full catalog every 5 min.** Simple, but the cost grows with the catalog
  and the store hosting limits. A full review stays a nightly job, later.
- **Spring cron instead of Temporal Schedules.** No run history, retries or per-store pause.
  Rejected (document 01: Temporal for background processes).

## Consequences

- The number of workflows grows with the number of variants that have events. Long-running workflows need
  versioning (`Workflow.getVersion`) on every logic change, as in B-08.
- The Temporal test server does not support Schedules. Tests call the workflow directly, and schedule creation is
  checked on the local Temporal (CI smoke).
- `modified_after` requires WooCommerce ≥ 5.8. Stores with an older version get an error in the connection check.

## Verification

- `OutboxDispatchTest`: a repeated event and a repeated dispatch give one workflow and one handled
  signal; `order.changed` reaches the workflow of every variant; a Temporal error postpones the event.
- `VariantInventoryWorkflowTest`: verification debounce, the authoritative read resolves `AMBIGUOUS`,
  `continueAsNew` keeps the identifiers, a replay test with a recorded history.
- `IncrementalSyncTest` with a fake Woo: the watermark moves only after all pages, a missed webhook is detected,
  coverage is extended only with a matching count.
- `IncrementalSyncTest`: health transitions `NOT_READY` → `STALE` → `DEGRADED` → `HEALTHY` → `DEGRADED` → `STALE`.
