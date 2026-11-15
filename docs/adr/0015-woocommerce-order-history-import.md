# ADR-0015: WooCommerce order history import (12 months) in the background

- Status: Accepted (PR merge, 2026-11-15)
- Date: 2026-11-14
- Related: ADR-0008 (initial import, completeness), ADR-0010 (schedules, watermarks, Integration Health), plan
  correction 2 (do not block the Inventory Guardian on the full history), backlog B-13b

## Context

The initial import (B-13) reads 14 days of orders, which is enough for the Inventory Guardian (7 full days). Analytics
and later decision types need 12 months. The plan correction says the full history must not block the first working
Inventory Guardian, so it has to arrive later, in the background, and must not change readiness or the order
coverage the guardian already relies on. A large store can have many thousands of orders, so the read has to be
resumable and gentle with the shop.

## Decision

1. **Target.** From the start of the store's local day 12 months before the initial import's window end, up to the
   initial import's window start. Later orders are already handled by the import and by reconciliation.
2. **Own watermark, moving backward.** `sync_watermark` gets the stream `HISTORY`. Its value is the earliest time
   from which all orders are known to be imported; it starts at the initial import's `window_from` and only moves
   back (`least`), never forward. The `PRODUCTS` and `ORDERS` watermarks are unchanged.
3. **Chunks.** One step reads `[max(target, watermark − 30 days), watermark)` with the same query, fields (no customer
   data) and count check as the initial import (ADR-0008 points 3–5). Only a chunk whose order count matches
   `X-WP-Total` moves the watermark; an incomplete or failed chunk is read again on the next run. Orders are upserted
   without outbox events: historical orders do not trigger stock verification or risk detection.
4. **Coverage only extends.** A complete chunk extends `order_sync_coverage.complete_from` back only when the chunk
   touches the current coverage. It never inserts or replaces coverage, so `complete_until`, which Integration Health
   and the metrics read, cannot move. If coverage was reset after a gap (ADR-0010), history orders are still stored,
   but the old period stays outside the coverage.
5. **Execution.** A third Temporal Schedule per connection, `woo-history-{connectionId}` (every
   `operator.automation.history.interval`, default 10 min, overlap `SKIP`), created by the existing schedule
   reconciler once the initial import has succeeded. Each run of `HistoryImportWorkflow` reads up to
   `max-chunks-per-run` (6) chunks, with `pause` (2 s) between them, as the system actor `woo-sync` with a
   `STORE_READ` scope. When the watermark reaches the target, the step does nothing beyond one read. The schedule
   interval is the retry backoff. `operator.automation.history.enabled=false` stops new schedules.
6. **Visibility.** Every chunk is a `sync_check` of kind `HISTORY` (orders read, status, error code). Integration
   Health ignores this kind, so the history never makes a store stale or degraded. Reaching the target writes the
   audit event `INTEGRATION_HISTORY_COMPLETED`, shown in Activity as an integration event. The integration response
   gets a `history` field (`status`, `importedFrom`, `target`), and Integrations shows it.

## Alternatives considered

- **Import 12 months in the initial import.** Simplest, but blocks the first useful result for hours on a large store.
  The plan correction rules it out.
- **One long workflow with `continueAsNew`.** Works, but needs its own start, recovery and cancellation path
  (FIX-02 style). A schedule already has all of that, and each run is short.
- **Coverage as several periods.** Would keep history after a coverage reset, but every metric query would have to
  handle gaps. Deferred until a reset is seen in practice.
- **Reading forward from 12 months ago.** The watermark would only become useful at the end; reading backward
  extends usable coverage chunk by chunk.

## Consequences

- Until the history is complete, WooCommerce receives up to 6 chunk reads per 10 minutes per store (more requests
  when a chunk has more than 100 orders). The 12 months take 12 chunks, so two runs, about 10 minutes, at the
  defaults.
- Orders of products deleted from the catalog are stored with lines without a variant, as in the initial import.
- A store reconnected to a different shop keeps the old `HISTORY` watermark for that connection id. Reconnection
  creates a new connection today, so this is not reachable yet.

## Verification

- `HistoryImportTest` against a stub WooCommerce: chunking and the target, the watermark moving only on a complete
  count, coverage extended back but never replaced, `complete_until` and Integration Health unchanged, no outbox
  events, the completed audit event and a no-op after completion, store isolation of the watermark.
- The workflow test runs chunks until completion within one run.
- `OpenApiContractTest` keeps the contract in sync.
