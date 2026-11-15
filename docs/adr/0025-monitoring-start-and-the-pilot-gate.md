# ADR-0025: Monitoring start and the pilot gate for execution

- Status: Proposed
- Date: 2026-11-15
- Related: ADR-0018 (onboarding steps), ADR-0020 (approval and execution), document 04 §12 (step 4 "Start
  monitoring", `POST onboarding/monitoring-start`), document 03 §12 (gate to the pilot),
  `docs/operations/pilot-procedure.md`, epic E-48

## Context

Onboarding step 4, "Start monitoring", has been a placeholder (`NOT_AVAILABLE_YET`) since ADR-0018. Document 04
describes it as the moment the store owner confirms the sources and the L0/L1 scope. Nothing ties that consent to
execution today: once the policy mode is `EXECUTE_ON_APPROVAL`, any store whose Meta connection can write would
accept approvals, including a store that never finished onboarding.

## Decision

1. **Recorded start.** `POST /api/v1/stores/{storeId}/onboarding/monitoring-start` with the onboarding version
   (`STORE_MANAGE`) records who started monitoring and when (`onboarding_progress.monitoring_started_at`,
   `monitoring_started_by`, migration V24) and writes the audit entry `MONITORING_STARTED`. It is refused while an
   earlier step is incomplete; a second call returns the state unchanged.
2. **Effective scope, computed.** The onboarding state returns the scope that is in force now, not a stored copy:
   analyses for the store (`ANALYSIS`) and, for ads, `NONE` (Meta skipped or not connected), `PROPOSALS_ONLY`
   (the policy mode is `EVALUATION_ONLY` or the connection is read-only) or `APPROVAL_REQUIRED` (L1). There is no
   automatic scope (L2) in the pilot.
3. **Execution requires the start.** Approving a decision and issuing an `AuthorizedAction` are refused with
   `MONITORING_NOT_STARTED` until monitoring was started for the store. Analyses and proposals do not depend on it:
   the shadow period needs them before anyone approves anything.
4. **The pilot procedure** (`docs/operations/pilot-procedure.md`) defines the stages (staging rehearsal,
   onboarding, shadow period, L1 gate, L1 in production), the shadow measures with their numerators, denominators
   and exclusions, and the rollback.

## Alternatives considered

- **Start monitoring gates the analyses too.** It would make the shadow period start only after the owner's
  click, but proposals before the start are harmless (nothing executes) and useful for the first review.
- **A per-store policy mode now.** The right end state (document 03 has per-store policy parameters), but it needs
  the policy screen; the start gate covers the pilot's single store without it.

## Consequences

- Stores that execute actions must have started monitoring; tests set this up explicitly.
- Stopping monitoring is not offered: the mutation stop (ADR-0020) is the immediate brake, the mode is the
  deployment-level one.

## Verification

- Onboarding tests: the start is refused before the earlier steps, recorded once, audited, and the effective scope
  follows the mode and the Meta connection.
- Execution tests: an approval and an authorization are refused with `MONITORING_NOT_STARTED` for a store that has
  not started monitoring.
