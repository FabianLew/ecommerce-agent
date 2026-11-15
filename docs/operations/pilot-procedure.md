# Pilot procedure: onboarding, shadow period and L1

How a pilot store goes from "connected" to "a person approves real ad pauses" (L1). Each stage has an entry
condition, what is checked during it and who decides to move on. The decisions are the project owner's together
with the store owner; nothing here moves a store forward on its own. L2 (actions without approval) is out of scope
for the pilot (plan decision I8: L2 only later, and only for a verified out-of-stock).

Related: ADR-0018 (onboarding), ADR-0020 (approval, execution, mutation stop), ADR-0021 (notifications, outcomes),
ADR-0022 (RLS, retention), ADR-0023 (environments), ADR-0024 (alerts), ADR-0025 (monitoring start),
[runbooks](runbooks.md), document 03 §12 (gate to the L2 pilot).

## Stage 0: staging rehearsal (test accounts)

Entry: staging is deployed (ADR-0023) with the WooCommerce test store and the Meta test ad account.

1. Run onboarding end to end on staging, including the Meta connection with `ads_management`.
2. Switch staging to `operator.policies.inventory-protection.mode=EXECUTE_ON_APPROVAL` and walk the Inventory
   Guardian scenario (document 03 §2): approve one pause of the test ad, see it `VERIFIED` in the
   ExecutionTimeline, the e-mail (or the logged delivery), and the outcome with its H24 checkpoint.
3. Rehearse the failure paths on the test account: stop mutations after an approval (the pause must not happen),
   revoke the approver's role before execution, and break the Meta token (`ConnectionFailing` alert fires).
4. Run the restore drill once (runbooks: *Restore drill*).

Exit: every step above passed and is written in the pilot log. Shadow mode never exercises the real write API, so
this stage is what proves L1 on Meta (document 03 §12).

## Stage 1: onboarding the pilot store

Entry: the store owner agreed to the pilot scope in writing (what is monitored, that ads are paused only after a
person's approval, who approves, who is called when something is wrong).

1. Members and roles: the store owner (`OWNER`), the people who may approve (`DECISION_APPROVE`), read-only
   members. Every approver has an e-mail address.
2. Onboarding steps in the app (ADR-0018): connect WooCommerce and wait for the import; connect Meta (read access
   is enough at this stage) or skip it; approve the business profile; verify the ad mappings of the dedicated ads.
   Confirm the stock semantics with the owner (document 03 §2: the stock read is the sellable stock).
3. **Start monitoring** (ADR-0025). The response shows the effective scope: analyses for the store, ad proposals
   only (`PROPOSALS_ONLY`) while the mode is `EVALUATION_ONLY`.
4. Alert routing: critical alerts (`ExecutionUnknown`, `ApiErrors`, `WorkerMissing`) page the on-call person; the
   others go to the pilot channel.

Exit: onboarding shows every step complete and the readiness of each domain as `READY` (or `SKIPPED` for Meta,
which ends the pilot at analyses only).

## Stage 2: shadow period (`EVALUATION_ONLY`)

At least 14 days, and at least 5 inventory risk cases (fewer cases extend the period). Proposals are created,
evaluated by the policy and shown for decision, but nothing is authorized or executed.

Weekly review with the store owner, per case: was the risk real, was the target ad the right one, would they have
approved, was anything missing from the evidence. Record each case with its outcome:

| Measure | Numerator | Denominator | Excluded |
|---|---|---|---|
| Precision of risk proposals | cases the owner would have approved | `RECOMMEND_ACTION` proposals | cases with known data gaps the owner flagged in advance |
| Correct target | proposals whose ad is the dedicated ad of the variant | `RECOMMEND_ACTION` proposals | — |
| Missed risks | stockouts with a running dedicated ad and no proposal | stockouts with a running dedicated ad | variants without a verified mapping |
| Agent agreement (if the agent is on) | cases where the agent's disposition equals the baseline's | cases analysed by both | runs that failed |

The quality report (`QualityReportTest`, live version with the model) is attached to the review. Agreement with
the baseline is a signal, not a requirement (CLAUDE.md).

## Stage 3: L1 gate

All of these must hold, and the project owner and the store owner both say yes:

- The runtime safety tests and the execution tests (authorization, isolation, mapping, timeout and retry,
  verification, fault injection) pass on the deployed version.
- Stage 0 passed on the same major version, and the restore drill passed within the last 30 days.
- The shadow review: no case where the proposed target was wrong, precision accepted by the store owner.
- Meta connection with write permission (`canWrite`), the mutation stop tested on production by the owner (stop,
  then resume).
- Alerts wired and acknowledged by the on-call person; the runbooks read.
- Retention periods approved (O-03), or a written decision to keep data until they are.

## Stage 4: L1 in production

1. Set `operator.policies.inventory-protection.mode=EXECUTE_ON_APPROVAL` for the worker and the API and deploy. The
   policy limits stay as approved (3 pauses in a rolling 24 hours, cooldown).
2. For the first week, the project owner reviews every executed action within a day: the ExecutionTimeline, the
   outcome and its checkpoints, and the e-mails.
3. Any wrong pause: follow *Responding to a wrong pause* in the runbooks, then decide with the store owner whether
   to continue or to go back to `EVALUATION_ONLY`.

## Rollback

At any stage: stop mutations for the store (immediate, no deployment), then set the mode back to
`EVALUATION_ONLY` and deploy. Approvals given while stopped are refused; proposals keep coming, so monitoring goes
on.
