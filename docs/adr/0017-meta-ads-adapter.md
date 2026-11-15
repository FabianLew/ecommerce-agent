# ADR-0017: Meta Ads adapter: connection, ad state polling and the pause write

- Status: Proposed
- Date: 2026-11-15
- Related: document 03 §9–10 (API scope, timeout after a write, verification), document 02 §5 (read tools),
  ADR-0007 (secrets, SSRF), ADR-0011 (ads and mappings), plan decision I8 (ads L1, L2 only for verified out of
  stock), backlog B-31, B-32, B-39

## Context

Ads and their mappings come from a fixture until now. The pilot needs the real state of the ads of one ad account
(own and effective status, creative) and, from F3, one write: pausing one ad after approval. Meta's Marketing API
is reached through the Graph API with an access token; the sandbox has no test account, so the adapter is built
and tested against a local stub of the API contract.

## Decision

1. **Separate connection.** A store has at most one Meta ad account connection (`meta_connection`, migration V16)
   with its own encrypted credential (`meta_credential`, AES-256-GCM as ADR-0007, associated data binding it to the
   connection, store and name). The WooCommerce connection model is unchanged. The token is accepted once, never
   returned; the API shows only the last four characters.
2. **Token and permissions.** A system user token of the store's Business Manager. `ads_read` is enough for the
   pilot in shadow mode; `ads_management` is needed for the pause (B-39). The connection check reads the account
   (`/act_{id}?fields=id,name,account_status,currency`) and the token's permissions (`/me/permissions`) and stores
   whether writing is possible; the API never exposes the token or the raw response.
3. **Fixed host, pinned version.** Requests go only to `https://graph.facebook.com/{version}` (version pinned in
   configuration, initially `v23.0`); the host is not taken from input. Only the `local` and test profiles may point
   the base URL at a stub. The token goes in the `Authorization` header, never in a URL or a log. `paging.next` links
   are followed only when they point to the same host and path.
4. **Polling the ad state.** The worker reads all ads of a connected account every 30 seconds
   (`id,name,status,effective_status,adset_id,campaign_id,creative{id}`, 100 per page) and upserts them with source
   `META_API`. The creative fingerprint is `meta-creative:{creative id}`, so a new creative invalidates a verified
   mapping (IG-09). An ad that is not returned keeps its last state and simply grows old; the policy's ad state
   freshness check (60 s) then blocks a pause. Rate limiting (HTTP 429, error codes 4, 17, 32, 613, 80004) ends the
   run and the next run waits for the `Retry-After` or a minimum backoff.
5. **The fixture stays for local development** and is not used once a store has a Meta connection.
6. **The pause write (B-39).** `POST /{ad-id}` with `status=PAUSED`, after a fresh read of that ad. The executor
   (ADR-0018) treats a timeout after the write as an uncertain result and reads the ad again; a write is verified
   only by a read showing `status=PAUSED`. The adapter offers no other write.

## Alternatives considered

- **Reusing `integration_connection`.** Its columns, actions and health are WooCommerce-specific; mixing providers
  would change every existing read model and UI. A separate table is a small, isolated addition.
- **Meta webhooks for ad changes.** They need an app review and a public endpoint per environment; polling a pilot
  account every 30 seconds is well within limits and gives the freshness the policy needs.
- **The Meta Java SDK.** It adds a large dependency for four calls; the JDK HTTP client with our own size and time
  limits matches the WooCommerce adapter.

## Consequences

- A real ad account and a system user token are needed to verify the adapter outside the stub; the contract tests
  pin the fields and the error mapping we rely on.
- Mappings for real ads are created and verified by the operator (B-32); a newly read ad has no mapping and is
  therefore never a pause candidate.

## Verification

- Contract tests against a local Graph API stub: paging, field mapping, errors and rate limits, the host rule,
  token never in the URL.
- Store isolation test for the connection API; the poller test upserts ads only into the connection's store.
