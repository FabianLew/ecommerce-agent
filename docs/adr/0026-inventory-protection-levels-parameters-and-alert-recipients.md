# ADR-0026: Inventory protection levels, versioned parameters and alert recipients

- Status: Proposed
- Date: 2026-11-15
- Related: ADR-0011 (risk detection), ADR-0012 point 5 (PauseAdPolicy), ADR-0020 (execution limits), ADR-0021
  (notifications), ADR-0025 (per-store action permission), plan decision I8, the project owner's decisions of
  2026-11-15 on the ad policy

## Context

The project owner set the ad policy for the pilot on 2026-11-15:

- L0: a warning when the confirmed cover drops below 5 days.
- L1: a recommendation to pause the dedicated ad when the confirmed cover is below 3 days. It is approved by the
  store owner or an authorised decision operator.
- L2: off by default. If it is ever switched on, it needs a separate activation by the store owner and applies only
  to a confirmed zero stock. At most 3 automatic pauses per store in 24 h, and only for verified mappings.
  Multi-product campaigns and ambiguity escalate. There are no autonomous budget increases.
- The alert owner is the store OWNER, with an optional deputy. No human response is never consent.
- Every threshold and limit is versioned policy configuration, not a code constant.

Today L1 exists (ADR-0012, ADR-0020, ADR-0025). There is no L0 warning. E-mails go to every member who may approve
decisions. The execution limits (`operator.execution.max-pauses-per-store-24h`, `cooldown-per-ad`) are deployment
settings outside the policy version that every evaluation records. The policy class still has an L2 branch behind a
flag (`l2-enabled`), which returns `ALLOW`.

## Decision

1. **L0: a stock warning, no action.** After every current risk observation (ADR-0011 point 4), a variant whose
   confirmed `daysOfCover` is below `warning-days` (5) gets one `STOCK_WARNING` notification per store-local day
   (deduplication key `stock-warning/{variantId}/{date}`). It carries the cover, the stock, the threshold and the
   detector version. It proposes nothing and opens no episode. Cover below `threshold-days` (3) still opens the
   episode of ADR-0011 as before; the warning of that day stays the only L0 message.
2. **L1 unchanged in substance.** A pause of the dedicated ad is recommended at confirmed cover below
   `threshold-days`. It is approved by a member with `DECISION_APPROVE`: the `OWNER` or a `DECISION_OPERATOR`, the
   role the owner gives to authorise someone. Execution also needs the store's `PAUSE_AD` level of ADR-0025.
3. **No response is never consent.** A proposal that nobody decides expires (ADR-0020 point 2) and nothing is
   executed. No timer, reminder or escalation turns silence into an approval, at any level.
4. **L2 is not available in this version.**
   - The policy's autonomy check returns `REQUIRE_APPROVAL` whatever is configured. The `L2` value and
     `l2-enabled` stay readable, but `CloudConfigurationCheck` refuses `l2-enabled=true`, and the check records
     `L2_NOT_AVAILABLE` when L2 is asked for.
   - A future L2 needs a revision of this ADR and ADR-0025, with at least these rules:
     - a store level above `APPROVAL_REQUIRED`, set only by the store owner with a reason;
     - only a confirmed zero stock (not low cover);
     - only `VERIFIED` single-variant mappings;
     - at most 3 automatic pauses per store in 24 h, counted with the L1 pauses;
     - a multi-product campaign or any ambiguity gives `ESCALATE`;
     - never a budget increase or any other action than a pause.
5. **Alert recipients.** E-mails of `STOCK_WARNING`, `DECISION_NEEDED` and the action outcomes go to the store's
   `OWNER` members and the store's deputy, if one is set. In the app every member of the store sees the
   notifications, as today.
   - The deputy is one member of the store with `DECISION_APPROVE`, kept in `store_alert_setting`
     (`deputy_user_id`, `version`, `changed_by`, `changed_at`).
   - Only a member with `STORE_MANAGE` sets or clears the deputy, from the version they saw. Every change is audited
     (`ALERT_DEPUTY_CHANGED`).
   - A deputy who loses the permission or the membership gets no more e-mails. The setting stays visible as
     inactive until it is changed.
6. **Versioned parameters.** All thresholds and limits that decide what is proposed, approved or executed belong to
   a named, versioned parameter set, recorded where it is used:
   - `inventory-risk` (detector): `threshold-days`, `warning-days`, `min-observations`, `min-gap`,
     `max-stock-age`. They are recorded in every observation (ADR-0011). Adding `warning-days` makes it
     `inventory-risk/2`.
   - `inventory-protection` (policy): the freshness limits, `max-verification-age`, and now
     `max-pauses-per-store-24h` (3) and `cooldown-per-ad` (24 h), moved from `operator.execution`. They are recorded
     in every policy evaluation, and the executor uses the limits of the version it was authorised under. This makes
     it `inventory-protection@1.1.0`.
   - A change of a default is a change of the version. A test pins a hash of each default parameter set to its
     version, so a changed default without a new version fails the build. Deployment overrides are allowed; they
     are recorded like the defaults, and `CloudConfigurationCheck` refuses an override unless the version is set
     explicitly with it.
   - Per-store parameters wait for the policy screen (document 03). Until then the parameters are per deployment.

## Alternatives considered

- **A warning for every observation below 5 days.** Stock changes are frequent; one warning per variant and day is
  enough to act on and does not train people to ignore it.
- **Keep e-mails to every approver.** Several people would be told the same thing, and nobody is clearly
  responsible. The owner decides who else is told by choosing the deputy.
- **Remove the L2 code entirely.** The value is part of the recorded parameters of past evaluations; keeping it
  readable and refusing it is simpler than a data migration.
- **Execution limits stay deployment settings.** They decide whether an action runs, so an evaluation that does
  not record them could not be explained later.

## Consequences

- A new notification kind with its text in both languages, and one more step in the variant workflow's activity.
- A store table `store_alert_setting` with forced RLS (ADR-0022) and a settings control.
- New observations are `inventory-risk/2` and new evaluations `inventory-protection@1.1.0`; old records keep their
  versions.
- The `operator.execution.max-pauses-per-store-24h` and `cooldown-per-ad` settings move to
  `operator.policies.inventory-protection.*`; the old names are refused at start-up so that a stale override is not
  ignored silently.

## Implementation status

Implemented on the pilot branch with migration V29:
- `StockWarnings` after every observation of the variant activities: one `STOCK_WARNING` per variant and day in
  the store's time zone, with the cover, the stock, both thresholds and the detector version; e-mail and in-app
  text in both languages;
- `PauseAdPolicy` always requires approval and records `L2_NOT_AVAILABLE` when L2 is configured;
  `CloudConfigurationCheck` refuses `l2-enabled=true`;
- `RiskDetectorSettings` (`inventory-risk/2`, with `warning-days`) and `InventoryProtectionPolicy` (`1.1.0`, with
  the pause limit and the cooldown) record their parameters with every observation
  (`inventory_risk_observation.detector_parameters`) and evaluation; `ExecutionLedger.admit` reads the limits
  from the evaluation the action was authorised under (an evaluation of `1.0.0` recorded none, so its only values,
  3 and 24 h, apply);
- `ParameterSetVersionTest` pins a SHA-256 of each default set to its version; the cloud check refuses a set that
  differs from its defaults without its own version; `operator.execution` refuses unknown keys, so the old names
  stop the start;
- `store_alert_setting` with `AlertSettings` and `GET/PUT /api/v1/stores/{storeId}/alert-settings` (`If-Match`
  `"alert-setting-{version}"`, the candidates are the members with `DECISION_APPROVE` who are not owners);
  e-mail recipients are the owners and the active deputy; the settings page has the control.

## Verification

- Detector tests: a warning below 5 days, one per variant and day; none at 5 days or more; none on a stale or
  unknown observation; the episode still opens below 3 days.
- Policy tests: L2 configured still gives `REQUIRE_APPROVAL` with `L2_NOT_AVAILABLE`; the evaluation records the
  limits; the cloud check refuses `l2-enabled=true`.
- Parameter tests: the pinned hashes of the default sets match their versions.
- Recipient tests: e-mails go to owners and the deputy only; a deputy without `DECISION_APPROVE` is refused; a
  deputy who lost the permission gets nothing; changes are audited and versioned.
- Expiry test: a proposal that nobody decides expires and nothing is authorised.
