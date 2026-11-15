# ADR-0017: Meta Ads adapter: connection, ad state polling and the pause write

- Status: Proposed
- Date: 2026-11-15
- Revised: 2026-11-15 (review: tiered polling; the manual system user token is a pilot-only solution)
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
2. **Token and permissions (pilot only).** For the pilot, the store owner creates a system user in their Business
   Manager, generates its token and pastes it into the connection form. `ads_read` is enough for the pilot in shadow
   mode; `ads_management` is needed for the pause (B-39). The connection check reads the account
   (`/act_{id}?fields=id,name,account_status,currency`) and the token's permissions (`/me/permissions`) and stores
   whether writing is possible; the API never exposes the token or the raw response.

   This manual token is a pilot solution, not the SaaS onboarding. It works for one store run together with its
   owner, but it asks a store owner to handle a long-lived credential and gives us no revocation signal. Before a
   second, self-service store is onboarded, a separate ADR decides the target flow:
   - Facebook Login for Business with our Meta app, app review and the needed access level for `ads_read` and
     `ads_management`;
   - the business-integration system user token created through that flow;
   - token expiry and refresh handling;
   - the deauthorization and data-deletion callbacks.
   `meta_connection` and `meta_credential` do not change for it: the flow changes how the credential is obtained,
   not how it is stored or used.
3. **Fixed host, pinned version.** Requests go only to `https://graph.facebook.com/{version}` (version pinned in
   configuration, initially `v23.0`); the host is not taken from input. Only the `local` and test profiles may point
   the base URL at a stub. The token goes in the `Authorization` header, never in a URL or a log. `paging.next` links
   are followed only when they point to the same host and path.
4. **Polling the ad state, in two tiers.** Only a few ads of an account matter for a decision at any time, so they
   are read often and the rest rarely.
   - *Hot set, every 30 seconds.* The hot set is every ad with a verified mapping to a variant that has an open
     Inventory Guardian episode, a pending approval or an execution not yet final. It is read by id in batches (the
     Graph API `?ids=` lookup, at most 50 ids per request) with the fields
     `id,name,status,effective_status,adset_id,campaign_id,creative{id}`. This keeps the policy's ad state
     freshness check (60 s) satisfiable for the ads that can be paused.
   - *Account sweep, every 15 minutes.* The sweep reads `/act_{id}/ads` with the same fields and 100 per page,
     filtered to ads that are not archived or deleted. Where the API allows it, it asks only for ads updated since
     the last sweep (`updated_time`). A full sweep runs at least once a day, so a missed change cannot stay hidden.
     The sweep finds new ads and mapping candidates; it is not used for freshness.
   - *Adaptive pace.* The poller reads Meta's usage headers (`X-Business-Use-Case-Usage`, `X-Ad-Account-Usage`)
     on every response. Above 75 % of a quota it doubles the hot-set interval and postpones the sweep, until the
     usage falls again. Rate limiting (HTTP 429, error codes 4, 17, 32, 613, 80004) ends the run, and the next run
     waits for the `Retry-After`, the header's reset time or a minimum backoff.
   - *Before a write*, the executor reads that one ad fresh, whatever the tiers did (point 6).

   Every read is upserted with source `META_API`. The creative fingerprint is `meta-creative:{creative id}`, so a
   new creative invalidates a verified mapping (IG-09). An ad that is not returned keeps its last state and simply
   grows old; the freshness check then blocks a pause. All intervals are configuration
   (`operator.meta.poll.hot-interval`, `sweep-interval`, `full-sweep-interval`).
5. **The fixture stays for local development** and is not used once a store has a Meta connection.
6. **The pause write (B-39).** `POST /{ad-id}` with `status=PAUSED`, after a fresh read of that ad. The executor
   (ADR-0020) treats a timeout after the write as an uncertain result and reads the ad again; a write is verified
   only by a read showing `status=PAUSED`. The adapter offers no other write.

## Alternatives considered

- **Reusing `integration_connection`.** Its columns, actions and health are WooCommerce-specific; mixing providers
  would change every existing read model and UI. A separate table is a small, isolated addition.
- **Meta webhooks for ad changes.** They need an app review and a public endpoint per environment, and they do not
  replace the fresh read before a write. They are reconsidered together with the SaaS onboarding ADR, which needs
  the app review anyway; tiered polling stays as the reconciliation path.
- **Polling the whole account every 30 seconds (the first draft of this ADR).** Simple, but the cost grows with the
  size of the account, not with the ads that matter. Large accounts would spend their quota on ads that can never
  be paused.
- **The Meta Java SDK.** It adds a large dependency for four calls; the JDK HTTP client with our own size and time
  limits matches the WooCommerce adapter.

## Consequences

- A real ad account and a system user token are needed to verify the adapter outside the stub; the contract tests
  pin the fields and the error mapping we rely on.
- The SaaS onboarding for Meta is an open decision (a new ADR, a Meta app and app review) and blocks self-service
  onboarding of further stores, not the pilot.
- Mappings for real ads are created and verified by the operator (B-32); a newly read ad has no mapping and is
  therefore never a pause candidate.

## Implementation status

The poller on the pilot branch reads all ads of the account every 30 seconds and backs off only on rate-limit
errors. After this revision is approved, a follow-up adds the hot set with batched id reads, the incremental and
full sweeps, and the usage-header pacing.

## Verification

- Contract tests against a local Graph API stub: paging, field mapping, errors and rate limits, the host rule,
  token never in the URL.
- Store isolation test for the connection API; the poller test upserts ads only into the connection's store.
- Poller tests against the stub:
  - The hot set contains exactly the ads of open episodes, pending approvals and unfinished executions, and is read
    in batches of at most 50.
  - The sweep uses the `updated_time` filter, and the full sweep runs at its interval.
  - High usage headers slow both tiers down.
