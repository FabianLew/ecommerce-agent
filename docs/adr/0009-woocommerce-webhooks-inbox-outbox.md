# ADR-0009: WooCommerce webhooks: registration, signature, inbox, normalization and outbox

- Status: Accepted (manual registration variant chosen by the owner, 2026-11-14)
- Date: 2026-11-14
- Related: document 01 §"Data flow", document 03 §3 and §11 (IG-04), document 04 §10, ADR-0004, ADR-0007,
  ADR-0008, backlog B-14 (B-15 and B-16 use the outbox)

## Context

B-14 accepts WooCommerce webhooks. It is the first public endpoint without OIDC: the store calls it, not a
user. Four things are hard to reverse here: how webhooks are created in the store (it affects the key permissions
from ADR-0007 and onboarding), how we identify the store and verify the sender, what we store (orders in a
webhook contain customer data) and how we protect stock from old events (IG-04).

Experiment on a local WooCommerce 11.2.1 (webhooks through wp-cli, a receiver in the compose network):

| Observation | Result |
| --- | --- |
| Creating an active webhook | POST `application/x-www-form-urlencoded` `webhook_id=N`, **without a signature** (ping) |
| Delivery headers | `X-WC-Webhook-Topic`, `-Resource`, `-Event`, `-ID`, `-Delivery-ID`, `-Source`, `-Signature` |
| Signature | `base64(HMAC-SHA256(raw body, secret))`, matches our computation |
| Variation stock change | `product.updated` with the variation object (`type=variation`, `parent_id`), a new `date_modified_gmt` |
| `processing` order | `order.created`, `order.updated` (identical body) and `product.updated` of the product with reduced stock and a new `date_modified_gmt` |
| Order cancellation | `order.updated` and `product.updated` with the stock returned |
| Order body | full `billing`, `shipping`, `customer_ip_address`, `customer_user_agent` |
| Webhook API version | wp-cli creates `wp_api_v2` (no `stock_status` in the product); the admin panel defaults to `wp_api_v3` |
| Delivery | `wp_safe_remote_request`: blocks private hosts and ports other than 80, 443, 8080; asynchronous through Action Scheduler by default |

## Decision

1. **Manual registration; the API key stays read-only.** Creating webhooks through REST requires a key with
   write access, and ADR-0007 assumes read-only access. In Integrations we show the delivery URL and the list of
   topics: `Order created`, `Order updated`, `Product created`, `Product updated`, API version "WP REST API
   Integration v3". The user creates them in the Woo admin (Settings → Advanced → Webhooks) with one shared secret.
   The browser generates the secret (`crypto.getRandomValues`, 32 bytes); the user pastes it into Woo and sends it
   to us through a Server Action. The backend stores it like the API keys (`integration_credential`, name
   `webhook_secret`, AES-GCM from ADR-0007) and never returns it. A missing or disabled webhook is detected by
   polling and reconciliation (B-16), so the webhook is an accelerator, not the only source.
2. **Endpoint.** `POST /api/v1/webhooks/woocommerce/{endpointKey}`, without OIDC. `endpointKey` is a random
   128-bit connection key (`integration_connection.webhook_endpoint_key`), not the connection or store
   identifier. The store and the organization come from the connection found by the key, never from the payload or
   headers. Order: size ≤ 1 MiB (otherwise 413) → the key exists (otherwise 404) → a ping (`webhook_id=N`,
   form-urlencoded) gets 200 without being stored, also before the secret is saved → a stored secret and an
   HMAC-SHA256 signature over the raw bytes, compared in constant time (otherwise 401) →
   a topic from the allowlist and JSON with an `id` field (otherwise 200 and nothing stored, so that Woo does not
   disable the webhook after 5 errors) → write to the inbox and 200. The body never reaches the logs. This is the only
   place where a query without a `TrustedStoreScope` looks up a connection (by key); after that the system scope
   `woo-webhook` from `StoreAccess.systemScope` applies (ADR-0004).
3. **Inbox with deduplication and minimization.** The `webhook_inbox` table: connection, store, topic, Woo webhook
   and delivery IDs, `payload_sha256` (of the raw body), minimized payload, receive time, status
   (`RECEIVED`, `PROCESSED`, `IGNORED`, `FAILED`), number of attempts, outcome. `UNIQUE (connection_id, topic,
   payload_sha256)`: a repeated delivery of the same body is the same row and a 200 without another mutation. Instead
   of the raw body we store only allowlisted fields, the same as in the import (ADR-0008): for orders the
   status, dates, amounts, currency and line items, for products the catalog and stock fields. Customer data is
   dropped before the write. This is a deliberate deviation from the "raw event" in document 03 §3; the hash of the
   raw body is enough for deduplication and diagnosis.
4. **Normalization in the worker.** `WebhookInboxProcessor` (`worker` profile) fetches `RECEIVED` rows every 2 s
   (`FOR UPDATE SKIP LOCKED`, batches of 50). For each one, in one transaction: update of the commerce data, write of
   the outbox events and the row status. An error rolls back the transaction and increments the attempt counter
   (retry with backoff up to 5 attempts, then `FAILED`, visible in Integration Health from B-16).
5. **Stock never goes back (IG-04).** For each variant in the payload we compare `date_modified_gmt` with the
   `source_updated_at` of the latest stock observation:
   - older: a stale event, nothing stored (`IGNORED`, outcome `STALE`);
   - equal and the same stock: a duplicate, nothing stored;
   - equal but different stock (two changes in the same second): ambiguous, nothing stored, and a
     `commerce.inventory.verification-requested.v1` event (authoritative read in B-15/B-16);
   - newer: a new `WEBHOOK` observation and `commerce.inventory.changed.v1`.
   A product payload without `stock_status` (a v1/v2 webhook) does not change stock; it only requests verification.
   A variable product with stock on the parent updates the `PARENT` variants. A variation of an unknown product
   requests catalog verification. Orders use the existing upsert protected by `source_updated_at` and emit
   `commerce.order.changed.v1` with the list of variants. Webhooks do not extend `order_sync_coverage`: completeness
   is confirmed only by the import or by reconciliation with a count (ADR-0008, B-16).
6. **Outbox.** The `outbox_event` table: store, event type, aggregate (`variant`, `order`, `product`), v1 payload,
   a `dedup_key` unique within the store, creation, dispatch and next attempt times. The key describes the state, not
   the delivery: `inventory:{variant}:{sourceUpdatedAt}:{stock}:{status}`, `order:{id}:{sourceUpdatedAt}:{status}`,
   and for a verification request `verify:{inbox}:{aggregate}`. The same state from two topics (`order.created` and
   `order.updated`) or reprocessing an inbox row does not create a second event. Dispatch to Temporal
   (signalWithStart with a deterministic ID) is added by B-15; until then events wait with `dispatched_at = null`.
7. **Locally.** Woo in Docker delivers to `http://host.docker.internal:8080` (a `host-gateway` entry in compose).
   The seed installs a mu-plugin that allows private hosts (`http_request_host_is_external`) only in the local
   compose. The public backend address is set by `operator.integration.webhook-base-url`.

## Alternatives considered

- **A key with write access and automatic registration through `POST /webhooks`.** The most convenient, but it
  breaks the least-privilege rule from ADR-0007 for every customer. To be reassessed for onboarding (F6), together
  with the `/wc-auth` flow. Rejected for the Pilot.
- **A secret generated by the backend and shown once.** Convenient, but the backend would return a secret,
  contrary to 04 §10. Rejected in favor of a secret from the browser.
- **The connection identifier in the URL.** It exposes an internal identifier and cannot be changed without
  deleting the connection. Rejected in favor of a separate key.
- **Normalization inside the HTTP request.** A faster path, but a second route next to the inbox retries. Woo
  delivers asynchronously anyway. Rejected.
- **Deduplication by `X-WC-Webhook-Delivery-ID`.** Woo assigns a new identifier on every delivery, so a manual
  "resend" and identical `order.created`/`order.updated` would not be duplicates. We store it only for diagnosis.
- **Trusting the order of arrival.** Woo does not guarantee order (Action Scheduler, resends). Rejected.

## Consequences

- Onboarding has one more manual step (4 webhooks in the Woo admin). The UI must guide it clearly.
- `date_modified_gmt` has one-second precision; two changes within one second trigger a verification instead of a
  write.
- The public endpoint accepts unauthenticated traffic until the signature is checked. The size limit and the
  index lookup limit the cost; rate limiting and an edge proxy come in F4.
- A webhook with a wrong secret on the Woo side ends with 401, and Woo disables the webhook after 5 errors. Health
  (B-16) shows the lack of events despite changes detected by polling.
- The inbox and the outbox grow; retention (e.g. 30 days for processed rows) comes together with audit retention in
  F4.

## Verification

- The experiment from the table above (wp-cli and a receiver in the compose network).
- `WooWebhookApiTest`: ping, bad and missing signature (401), unknown key (404), too large body (413), duplicate
  delivery (one row), no customer data in the inbox, store isolation.
- `WebhookNormalizationTest` (IG-04): an old event after a new one, a duplicate, the same second with different stock,
  a `PARENT` variation, an order older than the stored one, one outbox event on reprocessing.
- Manually: webhooks from the local Woo to the backend through `host.docker.internal`, a stock change visible as a
  `WEBHOOK` observation.
