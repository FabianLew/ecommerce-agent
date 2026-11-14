# backend

Spring Boot modular monolith (versions: [ADR-0001](../docs/adr/0001-stack-versions.md),
modules: [ADR-0003](../docs/adr/0003-module-convention.md)). One artifact, two profiles:

| Profile | Role |
| --- | --- |
| `api` | REST API for the frontend (port `PORT`, default 8080), `/actuator/health` |
| `worker` | Background processing (Temporal workers since B-08), no HTTP server |

## Requirements

- JDK 21 (Gradle toolchain), Docker (Testcontainers in tests).

## Commands

```bash
./gradlew check                                   # unit tests, integration tests (Testcontainers) and module boundaries
./gradlew bootRun --args='--spring.profiles.active=api'
```

Database connection: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (default: the local PostgreSQL from `infra/`).
OIDC: `OIDC_ISSUER_URI` (default: the `operator` realm of the local Keycloak) and `OIDC_AUDIENCE` (`operator-api`).

Observability (B-07): JSON logs (ECS) on stdout with `requestId`, `correlationId`, `traceId`, `spanId`.
Every response has the `X-Request-Id` and `X-Correlation-Id` headers (accepted from the client only in a safe
format). OTLP trace export: `OTEL_EXPORT_ENABLED=true`, address `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`
(default: the local Jaeger from `infra/`), sampling `TRACING_SAMPLING_PROBABILITY` (default 1.0).

The `local` profile (combined with `api` or `worker`) adds demo tenants that match the Keycloak users:
organization "Demo": `owner-a` owns "Sklep A" and is an analyst in "Sklep C", `owner-b` sees only "Sklep B".

```bash
./gradlew bootRun --args='--spring.profiles.active=api,local'
TOKEN=$(curl -s -d grant_type=password -d client_id=operator-dev-cli -d username=owner-a -d password=owner-a \
  http://localhost:8180/realms/operator/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/stores
```

## API (as of B-07)

| Endpoint | Description |
| --- | --- |
| `GET /api/v1/me` | Current user (registered on the first request) |
| `GET /api/v1/stores` | Stores the user is a member of, with roles |
| `GET /api/v1/stores/{storeId}` | Store details; no access and no store give the same `404 NOT_FOUND` |

Errors use the format from document 04 §3: `{code, message, fieldErrors?, retryable, requestId, currentVersion?}`,
e.g. `UNAUTHENTICATED` (401), `FORBIDDEN` (403), `NOT_FOUND` (404), `METHOD_NOT_ALLOWED` (405),
`INTERNAL_ERROR` (500, without exception details; the details are in the log with the same `requestId`).

## Temporal (B-08)

- The `worker` profile runs the workers (queue `operator-main`); the `api` profile uses only the client.
  Address: `TEMPORAL_ADDRESS` (default `localhost:7233`), namespace `TEMPORAL_NAMESPACE`.
- Workflows are started through `StoreContextPropagator.callWith(context, ...)`; activities read the scope
  only from `ActivityStoreScope.current()` (verified against the database by an interceptor, ADR-0004).
- Diagnostics (`local` profile only): `POST /api/v1/stores/{storeId}/diagnostics/workflow-scope-check`
  runs a workflow on the worker and returns the scope seen by the activity.
- Workflow tests run on the in-process `TestWorkflowEnvironment`; the replay test uses the recorded history
  `src/test/resources/temporal/scope-check-v1.json`. After an intended (versioned) workflow change:
  `./gradlew test -PrecordTemporalFixtures` and a new version of the file.

## Commerce model (B-11)

- Module `commerce`, migration `V3__commerce.sql`: `product`, `product_variant` (the selling unit), append-only
  `inventory_observation`, `customer_order` + `order_line` (no customer personal data),
  `order_sync_coverage` (the period for which orders are complete).
- Child tables reference their parent by `(id, store_id)`, so a row cannot point at another store's data.
- Upserts are idempotent and ignore data older than the stored `source_updated_at`.
- Available stock: `InventoryReading.availableToSell()` subtracts reservations only with the
  `SEPARATE_RESERVATIONS` semantics (IG-02, no double subtraction).

## Integrations (B-12)

- WooCommerce connection: `GET /api/v1/stores/{storeId}/integrations`, `POST .../integration-connections`,
  `POST .../integration-connections/{id}/credentials`, `POST .../integrations/{id}/checks` (ADR-0007).
- Secrets are encrypted with AES-256-GCM using the key from `INTEGRATION_SECRETS_KEY` (base64, 32 bytes; the
  application does not start without it). The `local` profile has a development key and allows `http://` and
  private addresses (local Woo).
- Test against a real local WooCommerce: `WOO_LIVE_URL=http://localhost:8081 ./gradlew test --tests '*WooCommerceLiveTest'`.

## WooCommerce import (B-13)

- `POST /api/v1/stores/{storeId}/integrations/{id}/sync-jobs` `{"scope":"INITIAL_14_DAYS"}` → 202 and a
  `sync_run`; status: `GET .../sync-jobs/{jobId}` or `lastSync` in `GET .../integrations`.
- The `WooImportWorkflow` workflow (`worker` profile) imports the catalog with stock levels, then the orders since
  midnight store time 14 days ago. The period goes into `order_sync_coverage` only when it matches `X-WP-Total`
  (ADR-0008).
- Locally: the API (`api,local`) and the worker (`worker,local --server.port=8090`), then the "Importuj dane z
  ostatnich 14 dni" (import data from the last 14 days) button in Integrations.

## WooCommerce webhooks (B-14)

- Public `POST /api/v1/webhooks/woocommerce/{endpointKey}` (no OIDC, ADR-0009): a random key in the URL
  identifies the connection, and the `X-WC-Webhook-Signature` signature (HMAC-SHA256 of the raw body)
  authenticates the store. A Woo ping (`webhook_id=N`) gets 200 without being stored, a bad signature 401, an
  unknown key 404, a body > 1 MiB 413.
- Secret: `POST /api/v1/stores/{storeId}/integration-connections/{id}/webhook-secret` `{secret}` (the browser
  generates it, and the backend never returns it). The delivery URL is in `webhook.deliveryUrl`, built from
  `WEBHOOK_BASE_URL`.
- `webhook_inbox` stores a minimized payload (no customer data), deduplicated by the SHA-256 of the body.
  Every 2 s the worker normalizes entries (`WebhookInboxProcessor`) and writes an `outbox_event` in the same
  transaction.
- Stock never goes back in time (IG-04): `InventoryRepository.recordIfNewer` compares `date_modified_gmt` with the
  latest observation (`STALE`, `DUPLICATE`, `AMBIGUOUS` → verification request, `RECORDED`).
- Tests: `WooWebhookApiTest`, `WebhookNormalizationTest`.

## Outbox → Temporal dispatcher (B-15)

- `EventDispatch` (worker, every 1 s) forwards `outbox_event` via `signalWithStart` to workflows with fixed
  IDs (ADR-0010): `inventory-{storeId}-{variantId}` (`VariantInventoryWorkflow`) and `store-sync-{connectionId}`
  (`StoreSyncWorkflow`). A workflow rejects a known `eventId`, so a redelivery does not create a second workflow.
- A verification request, and an order change without newer stock within `order-delay`, trigger one fresh
  read of the variant from Woo (`WooSourceRefresh`, observation `POLL`). An unknown product is loaded into the
  catalog.
- Delays: `operator.automation.inventory.verify-delay` (10 s), `order-delay` (60 s),
  `operator.automation.catalog.refresh-delay` (30 s). Replay test: `temporal/variant-inventory-v1.json`.

## Polling, reconciliation and Integration Health (B-16)

- Every minute the worker creates missing Temporal Schedules for connections with a successful import
  (`SyncSchedules`): `woo-poll-{connectionId}` (products and variations changed since the watermark, every 5 min)
  and `woo-reconcile-{connectionId}` (orders with an `X-WP-Total` comparison, every hour). Both run
  `IncrementalSyncWorkflow` as the `woo-sync` actor.
- Watermarks (`sync_watermark`) move only after a complete read; order coverage grows only when the count
  matches. Every run writes a `sync_check` with the number of changes and missed webhooks.
- `health` in the integration response is computed on read: `NOT_READY`, `STALE` (blocks analysis),
  `DEGRADED`, `HEALTHY`, with reason codes (`IntegrationHealthService`).
- Settings: `operator.automation.sync.poll-interval` (5 min), `reconcile-interval` (1 h),
  `schedules-enabled` (`false` in tests: the Temporal test server has no Schedules).

## Metrics Engine v1 (B-17)

- `InventoryMetrics.coverage(scope, variantId, asOf)` computes `UNITS_SOLD@v1`, `SALES_VELOCITY_7_FULL_LOCAL_DAYS@v1`
  and `DAYS_OF_COVER@v1` deterministically, without an LLM (document 03 §2, §4).
- Window: 7 full local store days before the `asOf` day, by `placed_at`; v1 qualifying statuses:
  `processing`, `completed`, `on-hold`. Fractions: scale 4, HALF_UP (IG-01: 18 / (49 / 7) = 2.5714).
- Missing data is `null` with a reason (`MetricReason`), never zero: incomplete orders in the window, no sales,
  untracked stock or stock shared between variants, no stock reading. A stockout in the window marks the forecast
  as constrained (IG-25).
- Golden tests: `InventoryMetricsGoldenTest` (IG-01, IG-02, IG-03, IG-25).

## Evidence and ContextSnapshot (B-18)

- `EvidenceStore` (module `metrics`) stores immutable evidence with a hash of the canonical record (`CanonicalJson`,
  ADR-0011). Identical content in a store is the same evidence; a database trigger rejects updates and deletes.
- `InventoryEvidence` persists forecast evidence (stock, sales, velocity, coverage) with lineage. Evidence is
  created only when a decision context is built, not for analytics queries.
- `ContextSnapshots` (module `decisions`) stores a snapshot once, with a list of evidence from the same store; it is
  read only through a `TrustedStoreScope`.

## Ads and SKU–ad mappings (B-20)

- `AdRepository` (module `commerce`): Meta ads (own and effective status, creative fingerprint) and mappings to
  variants, with a version that increases on every change. A verification applies to the creative it was
  performed for (`AdMapping.currentFor`).
- Until the Meta adapter exists (F2), the local worker loads `infra/fixtures/meta-ads.json` every minute
  (`operator.commerce.ad-fixture-file`): the dedicated ad `ad-9001` for `TSHIRT-BASIC-S` and the catalog ad
  `ad-9002`.

## Risk detector and Opportunity (B-19)

- `InventoryRiskDetector` (`inventory-risk/1`, module `decisions`) records an observation on every assessment: risk
  means cover below `threshold-days`; stock older than `max-stock-age` or an unavailable metric gives `UNKNOWN`,
  which breaks the series. An episode opens after `min-observations` current risk observations at least `min-gap`
  apart (`operator.inventory-guardian.risk.*`).
- `Opportunities`: at most one active episode per variant (unique index, IG-05); status transitions go through a
  state machine with a version check.
- `VariantInventoryWorkflow` (version `risk-detection`) assesses risk after a stock change and after a fresh read,
  and while a series is building it confirms the stock with another read from the store after `min-gap`.

## DecisionProposal v1 contract (B-21)

- The schema `knowledge/schemas/decision-proposal-v1.schema.json` (JSON Schema 2020-12) is put on the classpath as
  `schemas/…` and is the only source of the contract's shape (ADR-0012); examples are in `knowledge/schemas/examples`.
- `DecisionProposals.submit` wraps the proposer's recommendation in the v1 envelope and checks it with
  `ProposalValidator` (schema, process rules from `ProposalRules`, evidence and target in the snapshot). A valid
  proposal becomes the next revision of the episode (the previous one moves to `SUPERSEDED`); an invalid one is only
  an entry in `proposal_rejection` with codes (IG-10).
- `AdEvidence` (module `metrics`) stores the ad-state and mapping evidence that target validation relies on.

## Inventory Guardian in shadow mode (B-22)

- An open episode starts `InventoryGuardianWorkflow` (ID `inventory-guardian-{storeId}-{variantId}-{opportunityId}`,
  `REJECT_DUPLICATE`). Its steps are activities: `buildContext` (snapshot `inventory-context/1` with stock, demand,
  supply and ad evidence), `propose` (`BaselineProposer`), `validateAndPersist`, `evaluatePolicy`.
- `PauseAdPolicy` (`inventory-protection@1.0.0`, `operator.policies.inventory-protection.*`) checks, in order,
  isolation, integration health, freshness, risk and demand quality, mapping and creative, ad state and autonomy.
  The result, with the list of checks and parameters, goes into `policy_evaluation` in `EVALUATION_ONLY` mode;
  nothing is executed.
- The episode status follows from the disposition and the policy result (ADR-0012 item 6). Episode limits are in
  `operator.inventory-guardian.workflow.*`. The variant workflow passes further observations through the
  `riskUpdated` and `riskResolved` signals.

## Rules

- Modules are subpackages of `com.ecommerceagent`; the allowed dependencies are in each module's `package-info.java`.
- Store endpoints take a `TrustedStoreScope` as a controller parameter; queries filter by
  `organization_id`/`store_id` from that object (ADR-0004). Data access: `JdbcClient` + SQL (ADR-0005).
- Flyway migrations are in `src/main/resources/db/migration`; time is stored in UTC.
