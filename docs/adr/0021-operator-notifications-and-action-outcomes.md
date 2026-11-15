# ADR-0021: Operator notifications and action outcomes

- Status: Proposed
- Date: 2026-11-15
- Revised: 2026-11-15 (review: no exactly-once claim for e-mail; uncertain delivery, idempotency, reconciliation)
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
2. **E-mail is a delivery of a stored notification.** Recipients are the store members who may approve decisions
   (`DECISION_APPROVE`) and have an e-mail address. For each recipient there is exactly one `notification_delivery`
   row (unique on notification, channel `EMAIL` and recipient user), so the system never *decides* twice to tell
   the same person the same thing. Sending goes through `MailProvider` (I3); the pilot default is
   `LoggingMailProvider`, which only logs the delivery id. A real provider is chosen in O-11. E-mail text is English
   for now; the user's language is not stored yet.

   **Delivery is not exactly-once.** A message handed to a provider can be accepted while the
   answer is lost (a timeout, a crash between the call and the commit), and after that the system cannot know
   whether it was sent. The design states this instead of hiding it:
   - *States.* `PENDING → SENDING → SENT | FAILED | UNKNOWN`, then `EXHAUSTED` after the last attempt, and later
     `DELIVERED` or `BOUNCED` from provider events. `SENDING` is a claim with `claimed_until`, and its attempt
     counter is incremented before the call. An accepted call gives `SENT` with the provider's message id. A
     definite refusal before acceptance (validation, authentication, a 4xx that means "not taken") gives `FAILED`,
     retryable or permanent by code. A timeout, a connection reset after the request was written, a 5xx or an
     expired claim gives `UNKNOWN`, never `PENDING`.
   - *Idempotency.* Every attempt for a delivery carries the same idempotency key, the delivery id, and the same
     `Message-ID` header (`<{deliveryId}@{mail domain}>`). A provider that honours idempotency keys then accepts a
     repeated attempt without sending again, and mail clients can collapse a duplicate that still gets through.
     `MailProvider` declares its capabilities (`idempotencyKeys`, `statusLookup`, `events`), and O-11 records them
     for the chosen provider.
   - *Reconciliation before a retry.* The delivery sweep (`operator.notifications.retry-interval`, worker profile)
     never resends a `SENT` row. For an `UNKNOWN` row it first asks the provider by the idempotency key or message
     id when `statusLookup` exists, and records `SENT` if the provider has it. It resends only when the provider
     says it does not have the message, or when the provider honours idempotency keys. With neither, an `UNKNOWN`
     delivery is not resent automatically. It moves to `EXHAUSTED` once the reconciliation attempts are used up,
     and the in-app notification still tells the person. This is the rule of document 02 for outgoing mail: no blind resend after an uncertain
     dispatch. A stable `Message-ID` alone does not prevent a duplicate at the recipient.
   - *Provider events.* Delivered, bounced and complaint events from the provider (when `events` exists) are stored
     per delivery and move it to `DELIVERED` or `BOUNCED`. A bounce is not retried.
   - *Limits.* At most `operator.notifications.max-attempts` attempts with backoff, then `EXHAUSTED`, which the
     gauge of ADR-0024 counts. The in-app notification exists before any delivery and stays the source of truth: a
     lost or duplicated e-mail never changes it, the action or the outcome (IG-23).
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

- The e-mail provider for notifications is optional and usage-billed: Amazon SES or another EU SMTP provider behind
  `MailProvider` (ADR-0028). The demo may run with in-app notifications only.
- Migrations V21 (`operator_notification`, `operator_notification_read`, `notification_delivery`) and V22
  (`action_outcome`, `outcome_observation`) on the pilot branch, renumbered when it is merged.
- Real e-mail needs a provider (O-11) and its credentials; until then deliveries are recorded and logged. Choosing
  the provider includes its idempotency, status lookup and event support, and an endpoint for its events.
- Per-person notification settings (channels, quiet hours) come with Settings → Notifications later; the pilot
  sends to everyone with `DECISION_APPROVE`.
- Observations use the existing read models; spend stays `INCONCLUSIVE` until Meta insights are imported (F5).

## Implementation status

Implemented on the pilot branch with migration V27 (V21 unchanged):
- the states `UNKNOWN`, `EXHAUSTED`, `DELIVERED` and `BOUNCED`; deliveries V21 left `FAILED` for good became
  `EXHAUSTED` and any left `SENDING` became `UNKNOWN`;
- `MailProvider.send` answers `Accepted`, `Refused(code, retryable)` or `Uncertain(code)`, and an exception counts as
  uncertain;
- the stable `Message-ID` (`operator.notifications.mail-domain`), stored on the first attempt;
- the capability flags and `lookup`; reconciliation before a resend as point 2 describes;
- `OperatorNotifier.recordProviderEvent` with `notification_delivery_event`, once per provider event id. The HTTP
  intake arrives with the provider adapter (O-11);
- the gauge of ADR-0024 counts `EXHAUSTED`, and retention keeps a notification while a delivery is open or
  `UNKNOWN`.

`LoggingMailProvider` has no capability, so an uncertain delivery is never resent with it.

## Verification

- Notifier tests: deduplication, recipients by permission, read marks per person, store isolation (IG-23).
- Delivery tests on a fake provider:
  - A refused call gives `FAILED`, and a later sweep sends it.
  - A timeout after acceptance gives `UNKNOWN`, which is reconciled through the status lookup and becomes `SENT`
    without a second call.
  - Without a lookup, a provider with idempotency keys gets a resend with the same key and `Message-ID`, and a
    provider with neither capability gets no resend.
  - An expired claim becomes `UNKNOWN`; `SENT` is never resent; a bounce stops retries; attempts end in
    `EXHAUSTED`.
- Outcome tests: authorship for a normal write, reconciliation and NOOP; checkpoints, censoring and
  `INCONCLUSIVE` without data.
- Fault injection (B-43): a crash after the accepted write before the ledger is updated reconciles without a second
  write (IG-19); worker restart and replay while waiting for approval keeps the state and does not repeat a completed
  action (IG-24).
