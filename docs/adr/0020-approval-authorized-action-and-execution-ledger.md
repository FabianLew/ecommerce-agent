# ADR-0020: Approval, AuthorizedAction, the execution ledger and the mutation stop

- Status: Proposed
- Date: 2026-11-15
- Revised: 2026-11-15 (review: point 1 amended by ADR-0025; point 2 states how the proposal TTL, the approval
  window and freshness relate)
- Related: document 01 D-02, document 02 §9 (Policy Engine, AuthorizedAction, states) and §10 (Temporal),
  document 03 §7–§10 (L1/L2, Temporal, Meta idempotency, verification), document 04 §5 and §11 (approval and
  mutation-stop endpoints), ADR-0012 (proposal and policy), ADR-0014 (decision read API), ADR-0017 (Meta pause
  write), plan decision I1 (approval valid 24 h, revalidated before execution), backlog B-37..B-40

## Context

Until now the pilot only evaluates: the policy outcome is recorded, nobody can approve, and nothing is written to
Meta. Level L1 adds the first external mutation: a person approves one exact proposal revision, and the backend
pauses one dedicated ad. Every part of that path is hard to change later: the stored approval, the record that
authorizes the write, the ledger that makes retries safe, the limit on pauses and the switch that stops all writes.
The model must stay outside it: it proposes, and only trusted backend code approves, authorizes and executes.

## Decision

1. **Execution is opt-in per deployment.** `InventoryProtectionPolicy.mode` gets a second value,
   `EXECUTE_ON_APPROVAL`. `EVALUATION_ONLY` stays the default: approvals are refused with `SHADOW_MODE` and the
   workflow never waits. L2 (`ALLOW` without a person) stays disabled; nothing in this ADR enables it.
   *Amended by ADR-0025:* the mode is a deployment-wide ceiling. A store executes only when its own action
   permission for the action type is `APPROVAL_REQUIRED` as well.
2. **Approval (B-37).** `POST /decisions/{proposalId}/approvals` `{revision}` and `.../rejections`
   `{revision, reason?}` need the new permission `DECISION_APPROVE` (roles `OWNER` and `DECISION_OPERATOR`), an
   `If-Match` with the detail ETag and an `Idempotency-Key`. The service re-checks the membership
   (`StoreAccess.reauthorize`), then requires: the latest revision, status `PROPOSED`, not expired, disposition
   `RECOMMEND_ACTION`, the latest policy evaluation `REQUIRE_APPROVAL` for that revision, mode
   `EXECUTE_ON_APPROVAL` and no mutation stop. It stores a `decision_approval` row with the proposal's payload hash,
   the action hash (type, target, parameters), the policy evaluation id, the author and `expires_at` =
   min(proposal expiry, now + 24 h). One approval or rejection per proposal (unique); a repeated key with the same
   request returns the stored result, with another request `409 IDEMPOTENCY_KEY_REUSED`. The response is `202` with
   the approval id and an operation id; `200` never means the ad is paused. After the commit the API signals the
   workflow `approvalDecided(approvalId)`; the workflow deduplicates by id. A lost signal is harmless: the
   workflow also looks up a stored approval when it wakes.

   **Three different clocks.** They are kept apart on purpose:
   - *Proposal TTL* (`operator.inventory-guardian.workflow.proposal-ttl`, default 24 h from the proposal's creation) bounds when a
     decision can be made. After it the proposal is `EXPIRED`, approvals and rejections are refused, and the
     workflow stops waiting.
   - *Approval validity* is the 24 h of plan decision I1, but as an upper bound only:
     `expires_at = min(proposal expiry, approval time + 24 h)`. An approval never extends a proposal. With the
     default TTL of 24 h the proposal expiry is always the earlier one, so an approval given at hour 23 of a
     proposal is valid for one hour. A TTL longer than 24 h is allowed, and the 24 h cap then applies.
   - *Data freshness* is not measured by either clock. However much validity is left, the authorization (point 3)
     re-evaluates the policy on a fresh context, with the stock age and the ad state age limits of the policy,
     and the executor reads the ad again right before the write (point 6). An approval that is still valid but
     whose facts are stale, or no longer lead to the same action hash, is refused at authorization
     (`ACTION_CHANGED` or the policy's freshness code). A new proposal revision is then needed.
   `DecisionApprovals` computes the minimum, and the application refuses to start with a proposal TTL or an
   authorization TTL that is not positive.
3. **AuthorizedAction (B-38).** Only `ExecutionAuthority.authorize(scope, approvalId)` creates one, after a new policy
   evaluation on a fresh context that must again be `REQUIRE_APPROVAL` with the same action hash. The record holds
   the proposal id/revision/hash, the approval and policy evaluation ids, the action type, the target (ad id and
   external id), the parameter hash, the mapping version, the creative fingerprint, the expected status and the
   idempotency key `{store}/{connection}/{proposal}/{revision}/PAUSE_AD/{externalAdId}`. It is valid for 60 s. A
   model never sees or produces it; there is no API that creates it from a payload.
4. **The ledger.** `action_execution` has one row per idempotency key with states
   `PLANNED → IN_PROGRESS → APPLIED → VERIFIED`, or `NOOP`, `UNKNOWN`, `FAILED`, `CONFLICT`; illegal transitions
   are rejected in code and tested. It stores the pre-state, the request hash, the provider response summary, the
   attempt count and the reason. Every attempt starts by reading the ledger: `APPLIED`, `VERIFIED` and `NOOP` are
   returned as they are; `IN_PROGRESS` or `UNKNOWN` means reconciliation (read the ad, never a blind write).
5. **Serialization and limits.** A durable lease per target (`action_target_lease`: owner, `lease_until` 2 min)
   is taken before the fresh read and released at the end; an expired lease can be taken over. A limit slot is
   reserved in the same transaction as the `IN_PROGRESS` intent, under a per-store advisory lock, counting the
   store's pause executions in the last 24 h (`max-pauses-per-store-24h`, default 3). A retry of the same key does
   not take another slot; a `FAILED` result that never reached Meta releases it; `UNKNOWN` keeps it. A 24 h
   cooldown per ad blocks a second pause after an applied one.
6. **Executor (B-39).** `AdPauseExecutor.execute(scope, actionId)`: check the authorization (unexpired for a new
   write; an expired one may only reconcile), the mutation stop, `StoreAccess.reauthorize` of the approver, the
   lease, a fresh read of the ad through the new public facade `MetaAdActions` (the token stays in the integration
   module), compare status, creative and mapping with the authorization (`CONFLICT` on a material change), reserve
   the slot and record the intent, then write `status=PAUSED` once. An already paused ad gives `NOOP`. A timeout or
   a 5xx after the write gives `UNKNOWN`. `VerifyOrReconcile` reads the ad after 5, 15, 30 and 60 s of workflow
   time: own status `PAUSED` gives `VERIFIED`; otherwise `UNKNOWN` with an escalation. Nothing is undone
   automatically.
7. **Mutation stop (B-40).** A per-store `store_mutation_stop` (state, reason, version, who and when) and a global
   setting `operator.execution.mutations-enabled`. `GET/POST /mutation-stop` `{stopped, reason}` with `If-Match` of
   the version (ETag `"stop-{version}"`, version 0 before the first change): stopping needs `DECISION_APPROVE` or
   `STORE_MANAGE` and a reason; resuming needs `STORE_MANAGE`; both reauthorize. A rejection is still possible
   while stopped, because it changes nothing outside. The stop is checked when approving, when authorizing and right before the write; it never
   stops reads, verification or reconciliation of an already sent write.
8. **Workflow.** A new `Workflow.getVersion` branch: with `REQUIRE_APPROVAL` and mode `EXECUTE_ON_APPROVAL` the
   episode waits (status `AWAITING_DECISION`) for the approval signal or the proposal expiry. On approval it runs
   `authorizeAndExecute` (StartToClose 30 s, 3 attempts, idempotent by key), then the verification reads with
   durable timers, records the outcome and moves the episode to `ACTING` and then `RESOLVED` or
   `AWAITING_DECISION` (escalation). A rejection closes the episode with `REJECTED`. Old histories replay unchanged.

## Alternatives considered

- **An approval that authorizes directly.** Simpler, but the state may change between the click and the write; the
  documents require a new evaluation and a short authorization.
- **A database transaction held during the Meta call.** Rejected (document 03 §9): a slow API would hold locks for
  minutes; a lease with an owner and expiry gives the same serialization.
- **Relying on Meta for idempotency.** Meta does not offer it for this write; the ledger and set-state semantics
  give the process guarantee, with the documented race against a person editing in Ads Manager.
- **A kill switch only in configuration.** A store owner needs to stop writes without a deployment; the global
  setting remains for operators.

## Consequences

- Execution against a real ad needs a Meta test account, a system user token with `ads_management` and the
  L1 opt-in; until then the path is tested against the Graph API stub only.
- Migrations V18 (`decision_approval`), V19 (`authorized_action`, `action_execution`, `action_target_lease`,
  `action_limit_slot`) and V20 (`store_mutation_stop`) add store tables with the composite foreign key; on the pilot
  branch they are renumbered when it is merged.
- `allowedActions` in the decision detail is no longer always empty: `APPROVE` and `REJECT` appear when the rules of
  point 2 hold.
- Outcomes, notifications and fault injection (B-41..B-43) build on the ledger states defined here.

## Verification

- Unit tests of the ledger transitions and the limit/cooldown rules; API tests for approval preconditions,
  `If-Match`, idempotency keys, permissions and store isolation.
- Executor tests against the Graph API stub: success, already paused, conflict, timeout after the write, retry after a
  crash in `IN_PROGRESS`, expired authorization, mutation stop between approval and write (IG-14).
- A workflow test with the approval signal, its duplicate and a late approval for an expired proposal; replay of the
  recorded histories of the old path.
