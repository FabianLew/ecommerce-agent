# ADR-0011: Evidence, ContextSnapshot, risk detector and the Opportunity episode

- Status: Accepted (owner acceptance, 2026-11-14)
- Date: 2026-11-14
- Related: document 03 §3–4 and §12 (IG-04, IG-05), document 05 §C4 and §D2, document 02 §9, plan (rows 3 and 10
  of the review table, module map B2), ADR-0004, ADR-0008, ADR-0010, backlog B-18, B-19, B-20

## Context

I7 opens the first Inventory Guardian episode. Four things are hard to reverse here:

- the shape of the evidence and its hash, which proposals, approvals and the audit will refer to;
- the immutability of the context snapshot;
- the "two current risk observations" rule, which decides when an episode is created;
- the uniqueness of the active episode, which workflow deduplication depends on.

The plan (row 10) requires that evidence is persisted only in a decision context, not for every analytical
query.

## Decision

1. **Evidence (module `metrics`).** Every piece of evidence is a durable, immutable record in the `evidence` table
   with the fields:
   - store;
   - `kind` (`OBSERVATION`, `DERIVED_METRIC`);
   - `evidence_type` (`STOCK_READING`, `UNITS_SOLD`, `SALES_VELOCITY`, `DAYS_OF_COVER`, `REPLENISHMENT`,
     `AD_STATE`, `AD_MAPPING`);
   - `resource_ref` (`PRODUCT_VARIANT:{id}`, `AD:{id}`, `AD_MAPPING:{id}`);
   - the metric definition version;
   - canonical content (`content`);
   - `content_hash`;
   - `version` (always 1; new facts are new evidence);
   - the times `observed_at`, `available_at`, `computed_at`;
   - the list of input evidence.

   Evidence is created only in the Context Builder, never for analytical queries. A database trigger rejects
   `UPDATE` and `DELETE`. Identical content in the same store gives the same evidence (`UNIQUE (store_id, content_hash)`).
2. **Canonical record and hash.** The canonical record is JSON with keys in lexicographic order, without extra
   whitespace.
   - Decimal numbers are text in plain notation with the definition's fixed scale. Integers are numbers. We do not
     use floating-point numbers.
   - Time is stored in UTC in ISO-8601 format with second precision.
   - `content_hash = "sha256:" + hex(SHA-256(UTF-8))` is computed from the record without the identifier, the
     computation time and the write time, but with the store and the type.

   The same canonicalization applies to the snapshot and the proposal (ADR-0012).
3. **ContextSnapshot (module `decisions`).** `context_snapshot` contains:
   - the store;
   - the episode;
   - `as_of`;
   - the content schema (`inventory-context/1`);
   - the canonical content;
   - the hash.

   `context_snapshot_evidence` links the snapshot to the evidence. It is written once, and a trigger rejects changes.
   It can be read only through a `TrustedStoreScope`, so another store gets "not found". The snapshot is also the
   pilot's context manifest. References to knowledge and the store profile (05 §D2) will be added as extra fields in a
   new version of the content schema.
4. **Risk detector (module `decisions`, called from `VariantInventoryWorkflow`).** Every assessment writes an
   observation to `inventory_risk_observation` with the fields:
   - variant;
   - time;
   - stock confirmation;
   - `available`;
   - `days_of_cover`;
   - the outcome `AT_RISK`, `NOT_AT_RISK` or `UNKNOWN` with a reason;
   - the threshold and the detector version (`inventory-risk/1`).

   Rules:
   - An observation is current when the stock was confirmed by a read no older than `max-stock-age` (120 s). A
     confirmation is a source read or a fresh write from a webhook or polling.
   - `AT_RISK` means `daysOfCover < threshold-days` (3).
   - `UNKNOWN` (no forecast, stale stock, a variant without tracking or with shared stock) breaks the series.
   - An episode is opened by at least two consecutive current `AT_RISK` observations at least `min-gap`
     (5 min) apart.

   The variant workflow assesses risk after every stock change. After an `AT_RISK` observation without an episode it
   sets a timer for `min-gap` and then assesses again after a fresh read of the variant from Woo. This way the second
   observation does not depend on whether the store sends anything. The parameters come from the configuration
   (`operator.inventory-guardian.risk.*`) and are stored in every observation. Per-store settings will come with the
   store profile. A separate, faster threshold for a confirmed zero is left for later.
5. **Opportunity (module `decisions`).** `opportunity` contains the store, the `INVENTORY_RISK` category, the subject
   (`PRODUCT_VARIANT`, id), the status, the version, the times and the close reason.
   - A partial unique index `(store_id, category, subject_type, subject_id) WHERE status NOT IN ('RESOLVED',
     'CLOSED')` guarantees one active episode (IG-05).
   - Opening is `INSERT … ON CONFLICT DO NOTHING`, and on a conflict the existing one is read. A second detector gets
     the same episode.
   - The statuses are `OPEN`, `ANALYZING`, `AWAITING_DECISION`, `MONITORING`, `WAITING_DATA`, `ACTING`, `RESOLVED`,
     `CLOSED`. The table of allowed transitions is in code (`OpportunityStatus.canMoveTo`), and a change works as a
     compare-and-set on the version.
   - Proposals and executions have their own state machines (ADR-0012), so the Opportunity status describes only the
     lifecycle of the problem.
6. **Episode start.** After the episode is opened, the variant workflow calls an activity that starts
   `InventoryGuardianWorkflow` with the ID `inventory-guardian-{storeId}-{variantId}-{opportunityId}` and the
   `REJECT_DUPLICATE` policy. With an active workflow it sends it a risk update signal. A retried activity after a
   crash finds the same episode, and a start with the same ID does not create a second process. We do not use the
   outbox, because the initiator (the variant workflow) is already durable. The business events `inventory.risk.*.v1`
   will be added when a consumer appears (notifications, read model).
7. **Ads and mappings (module `commerce`, B-20).**
   - `ad` contains the `META` platform, the external ID, the name, the own and effective status, the creative
     fingerprint, the read time and the source (`FIXTURE`, later `META_API`).
   - `ad_mapping` contains the ad, the scope (`SINGLE_VARIANT`, `MULTI_PRODUCT`), a `version` incremented on every
     change, the verification status (`VERIFIED`, `UNVERIFIED`, `REVOKED`), the verification time and the creative
     fingerprint at the time of verification.
   - `ad_mapping_variant` links the mapping to the variants.
   - Until the Meta adapter exists (F2), ads and mappings are loaded from a fixture file in the `local` profile and in
     tests.

## Alternatives considered

- **Evidence computed for every analytics query.** Rejected in the plan (row 10): volume without need.
- **A hash of JSON written by Jackson without canonicalization.** Field order and number format depend on the
  library version, and the approval hash must be stable. Rejected.
- **The second observation only from the next event.** When stock does not change, there is no next event, and
  polling does not store identical stock. The risk would never open an episode. Rejected in favor of a timer and a
  fresh read.
- **Starting the episode workflow through the outbox.** It would be a second mechanism next to the durable variant
  workflow, with no reliability gain. Rejected for the pilot.
- **One Opportunity status with 16 values.** Rejected in the plan (row 3).

## Consequences

- A variant at risk causes an extra Woo read every 5 minutes until the episode opens. After it opens, assessment runs
  only on stock changes.
- Evidence and snapshots grow with the number of analyses. Retention comes with audit retention (F4) and must not
  delete evidence used in a decision.
- Changing the detector rule requires a new version (`inventory-risk/2`) and versioning of the variant workflow
  (`Workflow.getVersion`).

## Verification

- `EvidenceTest`: the canonical hash is stable regardless of field order, the trigger rejects a change, identical
  content gives the same evidence, another store sees neither the evidence nor the snapshot.
- `InventoryRiskDetectorTest`: one observation does not open an episode; neither do two closer than `min-gap`;
  `UNKNOWN` breaks the series; stale stock gives `UNKNOWN`.
- `OpportunityConcurrencyTest` (IG-05): two concurrent openings give one episode; a disallowed status transition
  is rejected.
- Variant workflow test: the second observation timer, one `InventoryGuardianWorkflow` start despite retries.
