# ADR-0014: Decision and activity read API

- Status: Accepted (PR merge, 2026-11-15)
- Date: 2026-11-14
- Related: 04 §3 (transport conventions, shared DTOs), 04 §5 (Decision Inbox and Detail), 04 §9 (Activity
  History), ADR-0004 (store scope, audit), ADR-0011 (evidence, snapshot, Opportunity), ADR-0012 (DecisionProposal
  v1, policy evaluation, shadow mode), backlog B-23

## Context

The Inventory Guardian stores episodes (`opportunity`), proposal revisions (`decision_proposal`), policy
evaluations, context snapshots and evidence, but nothing reads them over HTTP. B-24 needs a Decision Inbox, a
Decision Detail with three levels (summary, explanation, evidence) and an Activity list. Document 04 sets the
transport conventions (cursor lists, `meta`, ETag, 404 without hints). The API contract is consumed by a generated
client, so its shape is hard to change once the UI depends on it. The pilot runs in shadow mode: there are no
approval or rejection commands yet.

## Decision

1. **Endpoints** (all `GET`, `STORE_READ`, under `B = /api/v1/stores/{storeId}`):
   - `B/decisions?group=TO_APPROVE|MONITORING|COMPLETED&cursor&limit` returns `items`, `nextCursor`, `hasMore`,
     `counts` for all three groups and `meta`.
   - `B/decisions/{proposalId}` returns the `DecisionPresentation` of that revision, with an `ETag`.
   - `B/decisions/{proposalId}/evidence` returns the snapshot used for the decision and its evidence.
   - `B/activities?type&cursor&limit` returns `items`, `nextCursor`, `hasMore` and `meta`.
2. **An inbox row is an episode.** The list shows the latest proposal revision of each episode that has at least one
   valid proposal. Older revisions are reachable from the detail through `revisions`. An episode with only
   rejected (invalid) attempts has no row; the attempt is visible in Activity. Each revision has its own
   `proposalId`, so the detail needs no `revision` parameter.
3. **Groups are a projection of the episode status, not new states** (04 §5): `TO_APPROVE` is
   `AWAITING_DECISION`; `MONITORING` is every other active status (`OPEN`, `ANALYZING`, `MONITORING`,
   `WAITING_DATA`, `ACTING`); `COMPLETED` is `RESOLVED` or `CLOSED`. A proposal still `PROPOSED` after
   `expiresAt` is presented as `EXPIRED` (the workflow records the expiry later). The default group is `TO_APPROVE`.
4. **Ordering and cursor.** Rows are ordered by proposal `createdAt` descending, then id. The cursor is opaque
   (base64url of the last row's time and id) and only valid for the same group; a malformed cursor is `400
   INVALID_CURSOR`. `limit` is 1–100, default 25. Counters are computed separately and do not depend on the
   cursor.
5. **DecisionPresentation** follows 04 §3: `proposalId`, `opportunityId`, `revision`, `revisions`, `status`,
   `opportunityStatus`, `group`, `disposition`, `businessSummary` (the proposer's summary), `reasonCodes`,
   `uncertainties`, `missingRequirements`, `subject` (variant, SKU, product), `facts` (days of cover, threshold,
   available stock, units sold, velocity and stock confirmation time from the snapshot), `action` (for `PAUSE_AD`: the ad's
   id, external id and name), `excludedAds`, `evidenceRefs`, `policyEvaluation` (outcome, mode, decisive code,
   checks with facts), `proposer`, `createdAt`, `expiresAt`, `execution` (always `null` in the pilot) and
   `allowedActions`. In shadow mode `allowedActions` is empty and `blockedReason` is `SHADOW_MODE`; the UI shows
   the approval button disabled with that reason. The UI renders reason codes through its catalogs; the backend
   summary is shown as the proposer's own sentence.
6. **ETag.** The detail returns a strong ETag derived from the proposal's payload hash, its status, the status
   change time and the latest policy evaluation id. `If-None-Match` with the current value returns `304`. Commands
   in a later iteration will require `If-Match` with the same value.
7. **Evidence.** The evidence endpoint returns the snapshot (`id`, `asOf`, `contentHash`) labelled as the data used
   for the decision, and the evidence the proposal cites, plus the snapshot's other evidence, each with its type,
   kind, resource, times, definition version, content and content hash. The list is small and returned complete
   (`hasMore=false`). Evidence is loaded only by the store scope, so a proposal of another store is a `404` and its
   evidence ids cannot be probed.
8. **Activity is a projection of the audit trail.** `audit_event` rows of the store are listed for an allowlist of
   actions, each mapped to a type: `DETECTION` (episode opened or moved), `RECOMMENDATION` (proposal created,
   rejected as invalid, status changed), `POLICY` (policy evaluated) and `INTEGRATION` (connection, check, sync,
   webhook secret). Access-denied events stay in the audit only. The actor is `USER` (id and display name, never
   the e-mail) or `SYSTEM` (service name). Details are the audit's identifiers and codes, which ADR-0004 already
   keeps free of personal data. The decisions module now writes audit events for those episode, proposal and
   policy steps.
9. **`meta`** on every list and detail: `requestId`, `generatedAt`, `asOf` (the snapshot time for a decision,
   otherwise the read time), `timezone` (the store's), `dataVersion` (the ETag for a detail, otherwise `null`),
   `completeness` (`COMPLETE` or `PARTIAL`) and `warnings` (codes).

10. **Module access.** The web module reads policy evaluations through a `guardian` named interface of the
    automation module that exposes only `PolicyEvaluations` and its value types, not the workflow.

## Alternatives considered

- **One row per proposal revision.** Shows superseded revisions as separate work items and makes the counters
  misleading; rejected.
- **Group as a stored column.** Adds a second status to keep in sync with the workflow; the projection is cheap.
- **Offset pagination.** Rows move between groups while the user pages; a keyset cursor does not skip or repeat.
- **Activity as a new event table.** The audit trail already has the store, actor, resource and time; a second log
  would have to be kept consistent with it. If the activity view needs pre/post state, an `activities/{id}` detail
  can read the related resources.

## Consequences

- The generated client and the UI (B-24) depend on these shapes; changing them needs a new ADR or a versioned
  field.
- `activities/{id}` (the detail with pre/post state) is not part of this step; it arrives with action execution.
- Audit volume grows with every analysis (a few rows per revision); retention is still the open item from ADR-0004.
- Approval, rejection and reanalysis commands (04 §5) are not exposed; they need idempotency and `If-Match` and
  belong to the phase after the pilot's shadow mode.

## Verification

- `DecisionApiTest`: the section F scenario seeded through the real services; list groups and counters, cursor
  paging, detail fields, ETag and `304`, evidence content, a malformed cursor.
- `StoreIsolationTest`-style checks in the same test: a user of another store gets `404` for the list's proposal,
  its detail and its evidence, and sees no activity of the store.
- `OpenApiContractTest` keeps `contracts/openapi/operator-api.json` in sync.
