# ADR-0012: DecisionProposal v1, policy evaluation and InventoryGuardianWorkflow in shadow mode

- Status: Accepted (owner acceptance, 2026-11-14)
- Date: 2026-11-14
- Related: document 02 §9, document 03 §4–8 and §12 (IG-10), document 05 §E2, plan (decision C2, rows 2, 3, 7 and
  11 of the review table, decision I8), ADR-0002, ADR-0011, backlog B-21, B-22

## Context

A decision proposal is the contract between the proposer (today a deterministic baseline, from F2 the model) and the
backend. Approvals, policy evaluation, authorizations and the UI refer to it, so its shape is hard to change. Decision
C2 chose one v1 contract with the 1.2 semantics (five dispositions) instead of the 1.0 and 1.1 schemas and a migration.
Decision I8 says the pilot runs at L1, and L2 is disabled. B-22 ends with a proposal and a policy evaluation, without
mutations.

## Decision

1. **The v1 contract.**
   - **Envelope (set by the backend):**
     - `schemaVersion: "1"`, `proposalId`, `revision`;
     - `subject` in the form `{type: OPPORTUNITY, opportunityId}`;
     - `proposer` in the form `{kind: BASELINE|AGENT, name, version}`;
     - `agentRunId` (null for the baseline), `contextSnapshotId`, `createdAt`, `expiresAt`.
   - **The proposer's part:** `recommendation` with the fields:
     - `disposition` (`RECOMMEND_ACTION`, `NO_ACTION`, `MONITOR`, `INSUFFICIENT_DATA`, `ESCALATE`);
     - `action`;
     - `monitorPlan`;
     - `evidence` (a list of `{evidenceId, version}`);
     - `missingRequirements`, `reasonCodes`, `summary`, `uncertainties`.
   - **Action:** the first variant is `PAUSE_AD` with the fields `targetType: META_AD`, `targetId` (our ad
     identifier) and `parameters: {desiredStatus: PAUSED}`. There are no budget actions until their processes exist.
   - **Java:** sealed `DecisionSubject` (`OpportunitySubject` to start, `SupportCaseSubject` in the MVP) and sealed
     `ActionSpec` (`PauseAd`). No generic parameter maps.
   - Knowledge and store profile fields (`knowledgeReleaseRef`, `storeProfileRef`) will be added as optional fields.
     Every change in the meaning of a field is a new schema version.
2. **JSON Schema and the validator.** The 2020-12 schema is in `knowledge/schemas/decision-proposal-v1.schema.json`
   (closed fields and enumerations, `oneOf` for dispositions). It is put on the backend classpath and checked with
   the `com.networknt:json-schema-validator` 3.x library (Jackson 3). The validator has three layers:
   - **Schema:** structure and types.
   - **Domain:**
     - disposition invariants: `RECOMMEND_ACTION` requires an action, the others have `action: null`;
     - `monitorPlan` only with `MONITOR`, with deadlines within limits;
     - `missingRequirements` non-empty with `INSUFFICIENT_DATA`;
     - an action from the process allowlist;
     - reason codes from the process's closed list;
     - the result before `expiresAt`.
   - **Evidence:**
     - every piece of evidence exists in this store, has this version and belongs to the proposal's snapshot;
     - the evidence concerns the episode's variant or the target ad;
     - the action target is an ad with a mapping in the snapshot.

   A rejection gives error codes. An invalid proposal does not become a proposal, only a record of a rejected attempt
   (`proposal_rejection`) with the hash and the codes (IG-10).
3. **Storage and proposal states (module `decisions`).**
   - `decision_proposal` contains `UNIQUE (opportunity_id, revision)`, the snapshot, the proposer, the disposition,
     the canonical content (ADR-0011 item 2), `payload_hash`, the status, `created_at` and `expires_at`.
   - The content and the hash are immutable (trigger); only the status changes: `PROPOSED → APPROVED | REJECTED |
     EXPIRED | SUPERSEDED`.
   - A new analysis of the same episode is a new revision, and the previous active one moves to `SUPERSEDED`.
   - The execution state machine (`ActionExecution`) comes with the executor in F3.
4. **BaselineProposer (module `intelligence`).** A deterministic proposer behind the same contract is the reference
   point for evaluation and the fallback when the model fails. Rules, in order:
   - stale or unknown stock, no forecast: `INSUFFICIENT_DATA`;
   - cover not lower than the threshold: `NO_ACTION`;
   - no mapped ads: `NO_ACTION` (`NO_MAPPED_ADS`);
   - only multi-product or unverified mappings: `ESCALATE`;
   - a verified single-variant mapping with an active ad and a matching fingerprint: `RECOMMEND_ACTION` with one
     pause, and the multi-product ads are listed in the rationale as excluded.

   `MONITOR` is handled in the workflow, although the baseline does not return it to start with.
5. **PauseAdPolicy and PolicyEvaluation (module `automation`).** One typed policy class per action type. The order of
   checks follows 02 §9:
   1. isolation and the target in the store;
   2. integration health (`STALE` gives `DEFER`);
   3. validity and freshness (stock ≤ 120 s, ad state ≤ 60 s);
   4. risk and quality (a series of observations, a full demand window);
   5. mapping and creative (one variant, verification ≤ 24 h, matching fingerprint);
   6. ad state (`ACTIVE`, otherwise `DENY` with `ALREADY_PAUSED` or `CONFLICT`);
   7. autonomy: L1 gives `REQUIRE_APPROVAL`, L2 only with a flag that is disabled by default.

   The results are `ALLOW`, `REQUIRE_APPROVAL`, `DENY`, `DEFER`. `policy_evaluation` stores the proposal and the
   revision, the policy key and version (`inventory-protection@1.0.0`), the full parameters, the mode
   (`EVALUATION_ONLY` in the pilot), the result, the list of checks with facts and the time. The parameters are in the
   application configuration with an explicit version. Per-store configuration in the database comes with the policy
   screen. The pilot has no code that issues an `AuthorizedAction`.
6. **InventoryGuardianWorkflow (module `automation`).** Every step is an activity, and the workflow state holds only
   identifiers:
   1. `buildContext` (`ANALYZING`, a new snapshot);
   2. `propose` (baseline);
   3. `validateAndPersist`;
   4. `evaluatePolicy`.

   Episode status transitions depend on the disposition:
   - `RECOMMEND_ACTION` with `REQUIRE_APPROVAL` or `DENY`, and also `ESCALATE`: `AWAITING_DECISION`;
   - `RECOMMEND_ACTION` with `DEFER`: `WAITING_DATA`;
   - `NO_ACTION`: `CLOSED` (`NO_ACTION`);
   - `MONITOR`: `MONITORING` with a `nextReviewAt` timer and a limit of re-analyses;
   - `INSUFFICIENT_DATA`: `WAITING_DATA` with a timer and an attempt limit (then `AWAITING_DECISION`).

   A `riskUpdated` signal with a significant change of facts starts a new revision. `riskResolved` closes the episode
   (`RESOLVED`) and marks the active proposal as `SUPERSEDED`. A TTL timer sets `EXPIRED`. In shadow mode the workflow
   does not wait for approval: it ends when the episode is closed, or waits for signals up to the episode time limit.

## Alternatives considered

- **The 1.0 schema from document 03 and a later migration to 1.2.** Rejected by decision C2.
- **Validation only through Java records and strict deserialization.** The model in F2 will get a JSON schema, and the
  UI and quality tests need the same artifact. One schema in the repository, checked at runtime, avoids drift.
- **A general rule engine (OPA, Drools).** Rejected in the plan (row 11).
- **Storing an invalid proposal as `REJECTED`.** It would confuse a rejection by a human with a validation error.
  Rejected in favor of a separate attempts table.

## Consequences

- A new library, `json-schema-validator` 3.x. The schema is shared by the backend, the tests and the future prompt.
- The examples from document 03 (`ACT`, `WAIT_FOR_DATA`, `evidenceIds`) do not match v1. Tests and the UI use the new
  names.
- Changing the episode workflow logic requires `Workflow.getVersion` and a replay test.

## Verification

- `DecisionProposalSchemaTest`: the examples from `knowledge/schemas/examples` pass the schema and deserialization, and
  an unknown field, a wrong disposition and an action with `NO_ACTION` are rejected.
- `ProposalValidatorTest` (IG-10): fictitious evidence, evidence outside the snapshot, another store, a different
  target type and a target without a mapping are rejected before policy evaluation.
- `PauseAdPolicyTest`: every check and result; L1 gives `REQUIRE_APPROVAL`.
- `InventoryGuardianWorkflowTest`: the scenario from the plan (stock 18, 49 units in 7 days, `ad-9001` and the catalog
  ad `ad-9002`) gives `RECOMMEND_ACTION` for `ad-9001`, `REQUIRE_APPROVAL` and `AWAITING_DECISION` without mutations,
  plus a replay test.
