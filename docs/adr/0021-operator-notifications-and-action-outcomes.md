# ADR-0021: Operator notifications and action outcomes

- Status: Proposed
- Date: 2026-11-15
- Related: document 03 §8 (Notification activity limits) and §10 (verification, outcome, sample notification),
  document 03 IG-19/IG-23/IG-24, document 05 §F (outcome evaluation), document 04 (Activity Detail, Settings →
  Notifications), plan decisions I3 (MailProvider abstraction) and I7 (in-app and e-mail), ADR-0020, backlog
  B-41..B-43

## Context

After ADR-0020 a pause can be executed and verified, but nobody is told, and the ledger row is the only record of
the result. Two things are missing and both are hard to change later because other features build on them: the way a
person is told about an action (in the app and by e-mail, I7) and the record that separates the technical result
("the ad is paused") from what was observed afterwards (stock, sales, spend). The documents require that a
notification failure never undoes or repeats a pause (IG-23), and that an outcome without data is `INCONCLUSIVE`,
never a success.

## Decision

1. **Notifications are stored first, then delivered (B-41).** `OperatorNotifier.notify(scope, event)` writes one
   `operator_notification` row per deduplication key (`{executionId}/{status}` for an execution), with a kind,
   a severity, a reason code, structured parameters (never rendered text) and links to the opportunity, proposal
   and execution. The UI renders the text from the kind and parameters in the viewer's language. Read marks are per
   person (`operator_notification_read`).
2. **E-mail is a delivery of a stored notification.** For every recipient, a `notification_delivery` row
   (notification, channel `EMAIL`, recipient user, status `PENDING → SENT | FAILED`, attempts, error code) is unique,
   so a retry never sends twice to the same person for the same notification. Recipients are the store members who
   may approve decisions (`DECISION_APPROVE`) and have an e-mail address. Sending goes through `MailProvider`
   (I3); the pilot default is `LoggingMailProvider`, which only logs the delivery id. A real provider is chosen in
   O-11 and needs no change here. E-mail text is English for now; the user's language is not stored yet.
3. **Workflow step.** After the outcome is recorded, the episode runs `notifyOutcome(executionId)` as its own
   activity (StartToClose 10 s, 3 attempts). It is idempotent by key. When it finally fails, the workflow logs it
   and goes on: the pause stays confirmed and only the notification is retried later by the delivery sweep
   (`operator.notifications.retry-interval`, worker profile), never the action (IG-23).
4. **Technical outcome (B-42).** `ActionOutcomes.record(scope, executionId)` writes one `action_outcome` per
   execution once it is final (`VERIFIED`, `NOOP`, `UNKNOWN` after the last read, `FAILED`, `CONFLICT`): the
   technical result, the pre-state from the ledger, the post-state read last, the verification source
   (`META_READ`), the authorship (`OPERATOR` for a write we sent and confirmed, `UNCERTAIN` when the effect was found
   during reconciliation, `NONE` for `NOOP`/`FAILED`/`CONFLICT`) and the decision references (proposal, revision,
   approval, policy evaluation, authorization).
5. **ObservationPlan.** A `VERIFIED` outcome gets two checkpoints, `H24` and `D7` (the pilot's mature window),
   each `PENDING` with a due time. When due, the observer (worker profile, `operator.outcomes.observe-interval`)
   stores what the read models say for the variant and the ad since the verification: stock now and at the start,
   units sold, and Meta spend when insights exist. A checkpoint is `OBSERVED` when stock and sales are known for
   the window, otherwise `INCONCLUSIVE`; either way it lists limitation codes: always `ATTRIBUTION_NOT_CAUSAL`,
   `STOCK_INCREASED_IN_WINDOW` when a delivery or correction censors the stock comparison, `ORDERS_NOT_COVERED`,
   `STOCK_NOT_KNOWN`, and `SPEND_NOT_AVAILABLE` until insights exist. There is no "success" value and no saving is
   computed from the paused ad's spend.
6. **API.** `GET /notifications` (newest first, cursor, `unreadCount`), `POST /notifications/{id}/read`, and the
   decision detail gets an `execution` block (status, code, times, outcome and checkpoints) for the
   ExecutionTimeline (B-44). All of it is store-scoped and read-only except the read mark.

## Alternatives considered

- **Send e-mail directly from the executor.** Simple, but a mail error would sit inside the action's retry and could
  repeat or hide the action (IG-23).
- **Rendered notification text in the database.** Fixes one language and freezes wording; parameters keep the
  bilingual UI and future channels consistent.
- **A single outcome status with `SUCCESS`.** Rejected by document 05: a technical pause is not a business result,
  and a quiet window is not proof.

## Consequences

- Migrations V21 (`operator_notification`, `operator_notification_read`, `notification_delivery`) and V22
  (`action_outcome`, `outcome_observation`) on the pilot branch, renumbered when it is merged.
- Real e-mail needs a provider (O-11) and its credentials; until then deliveries are recorded and logged.
- Per-person notification settings (channels, quiet hours) come with Settings → Notifications later; the pilot
  sends to everyone with `DECISION_APPROVE`.
- Observations use the existing read models; spend stays `INCONCLUSIVE` until Meta insights are imported (F5).

## Verification

- Notifier tests: deduplication, recipients by permission, read marks per person, store isolation, a failing
  provider leaves the delivery `FAILED` and a later sweep sends it once (IG-23).
- Outcome tests: authorship for a normal write, reconciliation and NOOP; checkpoints, censoring and
  `INCONCLUSIVE` without data.
- Fault injection (B-43): a crash after the accepted write before the ledger is updated reconciles without a second
  write (IG-19); worker restart and replay while waiting for approval keeps the state and does not repeat a completed
  action (IG-24).
