# ADR-0018: Store Business Profile versions and onboarding progress

- Status: Proposed
- Date: 2026-11-15
- Related: document 05 §B (Store Business Profile), document 04 §12 (onboarding), ADR-0004 (scope, reauthorize),
  ADR-0017 (Meta connection), backlog B-33

## Context

The pilot needs the store's declared intent (goal, horizon, risk preference, planned ad spend) as data that a run can
pin, and an onboarding that shows per domain what is ready. Document 05 fixes the semantics: an approved version is
immutable, the goal is approved by a permitted user and never defaulted for them, missing fields are explicit, and the
profile never overrides the policy engine or grants a permission. Document 04 fixes the onboarding steps and that the
backend, not the browser, decides readiness.

## Decision

1. **Profile versions** (`store_business_profile`, migration V17, module `businessprofile`). One row per version with
   status `DRAFT`, `APPROVED` or `SUPERSEDED`; at most one draft and one approved version per store (partial unique
   indexes). Fields for the pilot: `goal` (`PROFITABILITY`, `GROWTH`, `BALANCED`, may be empty in a draft),
   `planningHorizonDays` (7–365), `riskPreference` (`LOW`, `MEDIUM`, `HIGH`), `taxBasis` (`EX_TAX`, `INC_TAX`),
   priorities from a closed list, a planned ad spend (minor units, currency, period `YYYY-MM`) and a short note. The
   currency is the store's. Costs and the economic model come later (F5); their absence is listed in `missingFields`.
2. **Editing and approval.** A user with `STORE_MANAGE` edits the draft (optimistic version check) and approves it
   with `StoreAccess.reauthorize`. Approval needs a goal, a horizon and a risk preference; it records `approvedBy`,
   `approvedAt` and `effectiveFrom`, marks the previous approved version `SUPERSEDED`, and is audited. An approved
   row is never updated again except for that status change. A new draft starts from the approved version.
3. **What the profile does not do.** It grants nothing: no goal raises a limit, and the policy engine keeps its own
   versions. Every field the draft leaves empty is reported in `missingFields`; analyses that need it are the ones that
   stop, not the store. Runs will reference `profileId@version` when the agent starts using it (after the quality
   report); this ADR does not change the Inventory Guardian context.
4. **Onboarding** (`onboarding_progress`, module `automation`, which may now read `commerce` and `businessprofile`).
   `GET /onboarding` computes the steps from the real state: (1) WooCommerce connected and the 14-day import
   finished, (2) Meta connected or explicitly skipped, (3) an approved profile, with the ad mapping count and stock
   semantics shown as readiness, (4) start monitoring (B-35/F4). It returns readiness per domain (`COMMERCE`, `ADS`,
   `PROFILE`, `AD_MAPPINGS`), the next step and a version. `PATCH /onboarding` stores only the current step and
   acknowledgements (such as `SKIP_META`) with `If-Match`; the browser cannot mark a step complete.

## Alternatives considered

- **One mutable profile row.** Simpler, but a run could not pin what the owner had approved, and document 05 requires
  immutability after approval.
- **A JSON document per version.** Flexible, but the fields are few and typed columns keep the validation and the
  queries simple; a later economic model gets its own tables.
- **Onboarding state as completed flags.** Rejected: the UI must not be able to declare a store ready.

## Consequences

- The Dashboard (B-35) can show readiness from the same computation.
- When costs arrive (F5), `missingFields` shrinks and the profile references the economic model version.

## Verification

- Profile tests: draft edit with a stale version is a conflict, approval needs the required fields and STORE_MANAGE,
  an approved version is never changed, a new approval supersedes the old one, store isolation.
- Onboarding tests: steps follow the real connection and import state; a PATCH cannot complete a step.
