# ADR-0025: Monitoring start, per-store action permission and the pilot gate

- Status: Proposed
- Date: 2026-11-15
- Revised: 2026-11-15 (review: monitoring consent separated from enabling L1; per-store, per-action permission)
- Related: ADR-0018 (onboarding steps), ADR-0020 (approval and execution; point 1 amended here), document 04 §12
  (step 4 "Start monitoring", `POST onboarding/monitoring-start`), document 03 §12 (gate to the pilot),
  `docs/operations/pilot-procedure.md`, epic E-48

## Context

Onboarding step 4, "Start monitoring", has been a placeholder (`NOT_AVAILABLE_YET`) since ADR-0018. Document 04
describes it as the moment the store owner confirms the sources and the L0/L1 scope. Today nothing per store
decides whether actions may be executed. Once the deployment-wide policy mode is `EXECUTE_ON_APPROVAL`, any store
whose Meta connection can write would accept approvals, including a store that never finished onboarding or whose
owner agreed only to monitoring. Agreeing to be monitored and agreeing that ads may be paused after approval are
two different consents. They are given at different times (the second only after the shadow period) and by a
deliberate act each.

## Decision

1. **Monitoring start is consent to analyses and proposals only.** `POST
   /api/v1/stores/{storeId}/onboarding/monitoring-start` with the onboarding version (`STORE_MANAGE`) records who
   started monitoring and when (`onboarding_progress.monitoring_started_at`, `monitoring_started_by`) and writes
   the audit entry `MONITORING_STARTED`. It is refused while an earlier step is incomplete, and a second call returns
   the state unchanged. Starting monitoring never enables execution.
2. **Action permission per store and action type.** A store table `store_action_permission` holds one row per store
   and action type (in the pilot only `PAUSE_AD`). Each row has a level, `version`, `changed_by`, `changed_at` and
   `reason`.
   - The levels are `OFF` (no proposals of that type are shown for decision), `PROPOSE_ONLY` (proposals are
     created and evaluated, never approved) and `APPROVAL_REQUIRED` (L1: a person may approve and the backend
     executes). There is no automatic level (L2) in the pilot; adding one needs its own ADR revision (plan
     decision I8).
   - A missing row means `PROPOSE_ONLY`, so a new store is never executable by default.
   - `GET/PUT /api/v1/stores/{storeId}/action-permissions/{actionType}` `{level, reason}` with `If-Match` of the
     version. Raising a level needs `STORE_MANAGE`, `StoreAccess.reauthorize` and a reason. It is refused with
     `MONITORING_NOT_STARTED` before monitoring was started, and `APPROVAL_REQUIRED` is refused with
     `META_WRITE_PERMISSION_MISSING` while the Meta connection cannot write. Lowering a level needs `STORE_MANAGE` or
     `DECISION_APPROVE`, like the mutation stop, so an approver can always step back.
   - Every change writes `ACTION_PERMISSION_CHANGED` with the old and new level.
3. **The deployment setting is an additional ceiling.** `operator.policies.inventory-protection.mode` stays, but it
   no longer enables anything by itself: `EVALUATION_ONLY` caps every store at `PROPOSE_ONLY`, and
   `EXECUTE_ON_APPROVAL` allows the stores whose own level is `APPROVAL_REQUIRED`. The effective level for a store
   and action type is the lowest of:
   - the store's permission;
   - the deployment ceiling;
   - the Meta connection (`PROPOSE_ONLY` when read-only, `OFF` when not connected or skipped);
   - the mutation stop (`PROPOSE_ONLY` while stopped, ADR-0020 point 7).

   The policy limits (pauses per 24 h, cooldown) apply on top, unchanged. The onboarding state and the settings
   page return the effective level with the reason for each cap. Analyses for the store (`ANALYSIS`) are on once
   monitoring was started and are not affected by any of these.
4. **Where it is checked.** Approving a decision and issuing an `AuthorizedAction` both require the effective level
   `APPROVAL_REQUIRED`, checked right before the write as the mutation stop is. The refusal codes are
   `MONITORING_NOT_STARTED`, `ACTION_NOT_PERMITTED` (store level), `SHADOW_MODE` (deployment ceiling),
   `META_NOT_CONNECTED` or `META_WRITE_PERMISSION_MISSING`, and `MUTATIONS_STOPPED`. A lowered level stops a pending
   approval from being authorized; it does not undo a write already sent, and verification and reconciliation go
   on.
5. **The pilot procedure** (`docs/operations/pilot-procedure.md`) defines the stages (staging rehearsal,
   onboarding, shadow period, L1 gate, L1 in production), the shadow measures with their numerators, denominators
   and exclusions, and the rollback. Enabling L1 for the pilot store is two acts: the deployment ceiling and the
   store's `PAUSE_AD` level. Each is recorded separately.

## Alternatives considered

- **Start monitoring gates the analyses too.** It would make the shadow period start only after the owner's
  click, but proposals before the start are harmless (nothing executes) and useful for the first review.
- **Monitoring start as the execution gate (the first draft of this ADR).** It made one click mean both consents,
  and execution still depended on a deployment-wide switch shared by all stores. Rejected in review.
- **Only a deployment-wide mode.** Enabling L1 for one store would enable it for every store whose connection can
  write, and going back needs a deployment.
- **Per-store policy limits now.** Document 03 has per-store policy parameters, but the pilot needs only the
  on/off level per action type; limits stay global until the policy screen exists.

## Consequences

- A new store table with RLS (ADR-0022) and an audited settings endpoint; the settings page gets one control per
  action type.
- Rollback of L1 for one store no longer needs a deployment: lowering the store's level is immediate and audited.
- Tests that execute actions set up monitoring start, the store permission and the ceiling explicitly.
- Stopping monitoring is still not offered: the action permission and the mutation stop are the brakes.

## Implementation status

Implemented on the pilot branch with migration V26 (V24 unchanged):
- `store_action_permission` with forced, fail-closed RLS (ADR-0022);
- `ActionPermissions`: the stored level, the effective level with each cap (`MONITORING`, `STORE`, `DEPLOYMENT`,
  `META`, `MUTATION_STOP`) and the audited change;
- `GET/PUT /api/v1/stores/{storeId}/action-permissions/{actionType}` with `If-Match` (`"permission-{version}"`);
- approval, `allowedActions` and `ExecutionAuthority` refuse with the codes of point 4, in that order;
- the onboarding state returns the effective level of `PAUSE_AD` with its caps (`monitoring.ads`, `adsCaps`);
- the settings page has the ad pause control.

A rejection is refused only by the store level and the deployment ceiling, the two sources that decide whether a
proposal is put to a person at all (the guardian waits for a decision only then). The Meta connection, a stop and
a missing monitoring start refuse an approval but not a rejection.

## Verification

- Onboarding tests: the start is refused before the earlier steps, is recorded once and audited, and does not
  change any action permission.
- Permission tests:
  - The default is `PROPOSE_ONLY`.
  - Raising a level is refused before monitoring start, with a read-only connection, without `STORE_MANAGE` or
    with a stale version.
  - An approver can lower a level, and every change is audited.
  - The effective level is the minimum of the four sources.
- Execution tests: an approval and an authorization are refused with each refusal code of point 4. With the
  ceiling at `EXECUTE_ON_APPROVAL`, a store at `PROPOSE_ONLY` cannot be approved while another store at
  `APPROVAL_REQUIRED` can. Lowering the level between approval and authorization prevents the write.
