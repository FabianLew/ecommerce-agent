# ADR-0027: AI cost governance, budget enforcement and abuse protection

- Status: Accepted (2026-11-15, after amendments A1 to A5 of the stage 1 review; see section 23)
- Date: 2026-11-15
- Related: ADR-0016 (agent runtime, ModelGateway, tools, knowledge), ADR-0004 (tenancy), ADR-0020 (approval and
  execution), ADR-0021 (notifications and outcomes), ADR-0022 (row-level security and retention), ADR-0024 (metrics
  and alerts), document 02 (AI agent architecture), document 05 (decision intelligence architecture)
- Applies to: agent runtime, ModelGateway, decision intelligence, Temporal, API security, SaaS economics
- Priority: required before paid AI operations are offered to customers

## 1. Context

Autonomous E-commerce Operator is a multi-tenant SaaS platform whose agents analyse and handle the business processes
of online stores on their own.

Unlike an application built only around an interactive chat, the platform can spend money on AI while nobody is using
the application. Sources of usage include:

- ad campaign analyses;
- interpretation of business anomalies;
- profitability diagnosis;
- report generation;
- replies to customers;
- re-analysis after the data changes;
- multi-step agent runtime processes;
- retries and recovery of Temporal processes.

Without central cost control we risk:

1. exceeding what a customer's subscription can pay for;
2. unbounded token usage by a faulty workflow;
3. analysing the same problem many times;
4. overloading the LLM providers' APIs;
5. one store exhausting the platform's shared limit;
6. abuse by users and misbehaving integrations;
7. unpredictable costs from timeouts, retries and agents running in parallel.

The bounded agent runtime (ADR-0016) sets the basic limits of a single run. It does not control financial budgets or
concurrency across stores, agents and workers.

**We need a mechanism that enforces the economic limits of AI use regardless of the model and of the source of the
call.**

## 2. Decision

We introduce a logical module, **AI cost governance**, responsible for AI budgets, admission control, cost
reservations, the usage ledger and abuse protection.

The module lives inside the existing Spring Boot modular monolith. We do not create a separate billing microservice.

### Main rules

1. Every paid model call needs authorization from the `AiBudgetGovernor`.
2. The budget is reserved atomically before the provider is called.
3. Limits apply at the platform, organization, store, automation, agent run and model call levels.
4. No agent or tool can raise its own limit.
5. All costs and reservations are persisted in PostgreSQL.
6. A provider-specific ModelGateway cannot be reached around the governed call path.
7. Cost is calculated from a versioned price list and the actual usage the provider returns.
8. An unknown call outcome never releases the budget automatically and never leads to unbounded retries.
9. Missing budget blocks new paid AI operations; it does not have to stop deterministic business processes.
10. The safeguards also cover work that runs autonomously in the background, without an active user.

The mechanism is independent of the Anthropic API, Amazon Bedrock and future providers.

## 3. Budget model

| Level | Responsibility |
|---|---|
| `PLATFORM` | Global AI cost budget |
| `ORGANIZATION` | Total budget of an organization |
| `STORE` | Budget of one store |
| `AUTOMATION` | Budget of one automation type in a store |
| `AGENT_RUN` | Maximum cost of a whole run |
| `MODEL_CALL` | Maximum cost of a single call |

The hierarchical budgets apply at the same time. Example: a store may still have budget, but the call is blocked when
the organization's or the platform's budget is exhausted.

### 3.1 Budget periods

We support:

- a monthly platform budget;
- monthly organization and store budgets;
- an optional daily safety limit;
- limits per automation and per run.

The backend computes budget windows and stores them as explicit time intervals. A subscription budget may follow the
subscription cycle rather than the calendar month. Changing a plan, the reset time or a budget allocation must not
lose billing history.

### 3.2 Currency and precision

Provider costs are stored in their original currency as `NUMERIC`, never `double` or `float`. USD is the technical
currency of AI cost accounting: prices, reservations, settlements and platform budgets are in USD. The user interface
does not assume one display currency: every amount it shows carries its currency code, and a converted amount is
labelled as converted, so PLN is one display currency among others, not a special case. When a plan budget is in PLN, conversion uses a versioned exchange rate with a
named source and validity period. The rate used at reservation and at settlement is recorded. Budgets keep a safety
margin for price and exchange rate changes.

## 4. Budget reservation and settlement

A cost is reserved before every model call.

### 4.1 Flow

```text
Agent / Workflow
      |
      v
Model call request
      |
      v
AiAdmissionController
      |
      v
AiBudgetGovernor
      |
      +-- DENIED --> no AI call
      |
      v
Atomic budget reservation
      |
      v
Provider ModelGateway
      |
      v
Usage and cost calculation
      |
      v
Settlement / uncertain outcome
      |
      v
AiUsageLedger
```

### 4.2 Cost estimate

Before the call we:

- fix the model and the provider;
- set the input and output token limits;
- estimate the input tokens and add a safety margin;
- include the overhead of tools and of any extra paid features;
- compute a conservative maximum cost from the approved price list.

The maximum cost estimate never assumes a prompt cache hit. When the maximum cost cannot be bounded reliably, the call
is rejected, or its configuration must change to a variant with a predictable limit.

### 4.3 Atomic reservations

A reservation checks every applicable limit in one PostgreSQL transaction and stays correct with several API instances
and Temporal workers. Allowed mechanisms: row locks, atomic conditional updates, a deterministic order of locking the
counters, unique idempotency keys.

An implementation of this shape without transactional protection against concurrency is not allowed:

```text
read available budget
if sufficient:
    execute AI
    update budget
```

A reservation has a unique id and the key of the logical request. Processing the same reservation command again must
not reduce the available balance again.

### 4.4 Settlement

After the provider answers we read the actual usage, compute the cost with the right price list version, record the
usage and the provider's call id, finalize the reservation and release the unused part. Settlement is idempotent.

When the actual cost exceeds the reservation, the system records the full amount, shows the overrun, tightens
admission control and raises an alert. The recorded cost is never capped at the reserved amount to hide an overrun.

### 4.5 Uncertain outcomes

After a timeout, a dropped connection or a worker crash it is not always known whether the provider charged the call.
Reservation states:

- `RESERVED`
- `IN_PROGRESS`
- `SETTLED`
- `RELEASED`
- `UNKNOWN`
- `RECONCILED`

For `UNKNOWN` the reservation is kept until it is reconciled with provider data or settled conservatively. An
ambiguous call is never retried automatically without a new budget evaluation.

A failed call is settled at zero (confirmed not charged) only when an explicit, versioned rule of its provider adapter
says so. The rule depends on the provider, the stage the request reached, the HTTP status and the provider's error
type together; a status code alone is never enough, and anything the rule does not list stays possibly charged:

| Stage | Outcome |
|---|---|
| Not sent (the adapter refused before sending: no model configured, deadline already passed) | Not charged |
| Error response before any output, with a status and error type the rule lists | Not charged |
| Error response with any other status, an unexpected error type or no readable error type | `UNKNOWN` |
| Error after a streaming response started (an error event after `message_start`) | `UNKNOWN` |
| No response (timeout, dropped connection, I/O error) | `UNKNOWN` |

Rule set `anthropic-charge/1` lists these pairs for the Anthropic Messages API: 400 `invalid_request_error`, 401
`authentication_error`, 403 `permission_error`, 404 `not_found_error`, 413 `request_too_large`, 429
`rate_limit_error`, 529 `overloaded_error`. Everything else, including 402 `billing_error`, 408, 409, 422, every other
5xx and `timeout_error`, stays `UNKNOWN`. Changing the list is a new rule set version with its regression test. A
provider without its own rule set (for example Bedrock until its adapter has one) treats every failure after the
request left as `UNKNOWN`.

Reconciliation tells apart: confirmed not charged, confirmed charged, still unknown. We do not assume every provider
can report the cost of a single request. Reconciliation jobs have a time limit and an operational escalation. An
uncertain reservation is never deleted without a trace.

## 5. AI run limits

Every automation has a versioned AI execution policy. Example:

```yaml
policy: marketing-diagnosis
version: 1

maxModelCalls: 4
maxToolCalls: 12
maxInputTokensPerCall: 12000
maxOutputTokensPerCall: 1500
maxRunDurationSeconds: 120

maxRunCostUsd: 0.50
maxRepairAttempts: 1
maxModelRetries: 1

maxConcurrentRunsPerStore: 2
cooldownMinutes: 360
```

These values are example starting settings, not a policy for every agent.

Rules:

- the backend sets the limits and the agent cannot change them;
- every model call needs its own reservation;
- the per-call limit does not replace the per-run limit;
- retries use the shared run budget;
- exceeding a limit ends the analysis in a controlled state;
- a run records its policy version for audit and reproducibility.

The versioned run policy with the ordinary run limits above (calls, tokens, wall time, cost per run, concurrency,
cooldown) is read when the run starts and applies until the run ends; the run records the version it used, and a new
version applies from the next run. The following always apply to a running run before every next paid call,
whatever its policy version says:

- the current budgets at every level (each call needs its own reservation against them);
- the AI kill switches (global, per store, per automation; section 12);
- a revoked authorization: the actor of the run is checked against the database before each call, so a removed
  membership or store stops the run;
- the safety blocks of governance itself: a missing or stale price, and a failure of governance, send nothing.

A reservation already granted does not allow a call after a later AI stop.

## 6. Rate limiting and concurrency

We keep apart **rate limiting**, which protects the availability of the API and the infrastructure, and **budget
limiting**, which protects costs.

### 6.1 User and integration limits

Limits per user, organization, store, endpoint, automation type, webhook source and globally for the platform. A
webhook must pass authenticity verification and deduplication before it can start a paid analysis. The backend never
accepts parameters such as the model, `maxTokens`, the maximum number of steps or a run budget from a client without
validation.

### 6.2 Concurrency control

The `AiAdmissionController`:

- limits active runs per store;
- limits parallel provider calls;
- controls RPM/TPM according to the provider's limits;
- prioritises processes;
- prevents one tenant from starving the other stores;
- limits queue length and waiting time.

The limits apply across all application instances, not inside one JVM. In the MVP durable counters, leases and
budgets live in PostgreSQL and the process queue in Temporal. We do not add Redis only for limiters. A call is admitted
only with both a free concurrency slot and a granted budget.

## 7. Event deduplication and analysis suppression

Not every business event needs a model. We reuse signal detection, opportunity management, deduplication and the
deterministic metrics engine, and add:

- **Opportunity fingerprint.** Identical signals about the same business problem update the existing opportunity
  instead of starting independent paid analyses.
- **Cooldown.** An expensive analysis is repeated only after a cooldown, unless the situation changed materially.
- **Material change detection.** The agent may run earlier when an important fact changes, for example a product
  reaches zero stock, a campaign crosses a risk threshold, new information needs intervention, or the current
  recommendation is no longer valid.
- **Batch processing.** Related signals may be analysed together when store isolation and the correctness of evidence
  are kept.
- **Retry protection.** A workflow retry or a redelivered event never creates a new paid agent run for the same
  logical request automatically.

## 8. Model routing and token optimization

A `ModelRoutingPolicy` runs before the ModelGateway and considers the task type, the required capability, the
available budget, quality evaluation results, model availability, allowed data processing regions, and cost and
throughput limits.

Preferred strategy:

1. deterministic analysis when it is enough;
2. a cheaper approved model for simple tasks;
3. a stronger model for complex analyses that need interpretation.

We never lower quality automatically for business-sensitive processes only to save cost. Every model must pass the
evaluation for its task class.

**Context optimization.** The context builder loads only relevant skills, never sends the whole store history, uses
aggregates from the metrics engine, limits tool result sizes, removes repeated context and uses versioned data
structures.

**Prompt caching.** Caching instructions and other repeated fragments is allowed when the provider supports it for the
chosen model and region. Customer data stays isolated between tenants. Cache write and read prices must be in the price
catalog. Caching is never the only way of keeping to the budget.

## 9. AI pricing catalog

A versioned `AiPricingCatalog`. Each entry names the provider, model and variant, region or inference profile,
currency, input and output price, cache write and read price where relevant, extra charges for paid features, the
effective date, the price source and the date of the last verification.

A price change creates a new tariff version. Historical call costs are never recalculated. When the price list is
unknown, stale or does not cover an active model feature, new paid calls are blocked by default. A new provider or
model needs its tariff added and tested. Actual costs are reconciled periodically with the providers' billing reports.

## 10. Module architecture

Proposed components:

```text
ai-governance/
├── api/
│   ├── AiBudgetGovernor
│   ├── AiAdmissionController
│   └── AiUsageQueryService
├── budget/
│   ├── BudgetPolicy
│   ├── BudgetReservation
│   └── BudgetRepository
├── usage/
│   ├── AiUsageLedger
│   ├── UsageSettlement
│   └── UsageReconciliation
├── limits/
│   ├── AiRunLimiter
│   ├── AiConcurrencyLimiter
│   └── AiRateLimiter
├── pricing/
│   ├── AiPricingCatalog
│   └── CostCalculator
└── routing/
    └── ModelRoutingPolicy
```

This is a logical split; it does not mean separate services or deployment artifacts.

### Platform independence

The product direction is a platform-independent Commerce Operator. AI governance therefore knows nothing about
commerce or advertising platforms (WooCommerce, Shopify, Meta Ads, Google Ads or any other). Its only dimensions are
the trusted organization and store context (`TrustedStoreScope`, the store as a tenant, not as a platform
connection), the automation type, the provider and the model. A new commerce or ad integration never changes the
governance module. The module depends only on `identity`; an architecture test fails the build when it depends on an
integration module or names a platform.

### Integration with the agent runtime

```text
AgentRuntime
    |
    v
GovernedModelGateway
    |
    +-- admission control
    +-- cost estimation
    +-- budget reservation
    +-- provider execution
    +-- usage settlement
    |
    v
Provider ModelGateway
```

`GovernedModelGateway` is the only production path to a paid model. Provider ModelGateways stay behind the module's
internal boundary; the module structure and Spring Modulith tests prevent business modules from using provider
adapters around governance.

### Contracts

Reference interface:

```java
public interface AiBudgetGovernor {

    BudgetReservation reserve(
        TrustedStoreScope scope,
        AgentRunId runId,
        AiCallEstimate estimate
    );

    void settle(
        UUID reservationId,
        ModelUsage usage
    );

    void markUncertain(
        UUID reservationId,
        String reason
    );
}
```

The types are illustrative. The final implementation follows the existing Java conventions and the ADR-0016
contracts (see section 22).

## 11. Data model

Proposed tables:

- `ai_budget_policy`: id, organization_id, store_id (null for a higher scope), automation_type (nullable),
  period_type, period_start / period_end, amount_limit, currency, policy_version, enabled.
- `ai_budget_balance`: budget_policy_id, spent_amount, reserved_amount, version.
- `ai_budget_reservation`: id, organization_id, store_id, agent_run_id, model_call_id, idempotency_key,
  estimated_max_cost, reserved_amount, settled_amount, currency, state, created_at, settled_at.
- `ai_usage_event`: id, organization_id, store_id, automation_type, agent_run_id, model_call_id, provider, model,
  pricing_version, input_tokens, output_tokens, cached_tokens, cost, currency, outcome, created_at.
- `ai_pricing_catalog`: id, provider, model, region, price_version, pricing_details, currency, effective_from,
  verified_at.

Required: indexes by store scope and period, unique idempotency keys, history of policy changes, strict tenant
isolation, atomic financial operations, no prompt content in the usage ledger, and retention enforced separately. The
model links to the existing `organization`, `store` and `agent_run` tables.

## 12. Abuse protection

- **Public API:** authentication, store authorization, rate limiting, request size limits, limits on how often
  analyses can be started, protection against double clicks and repeated commands.
- **Webhooks:** signature verification, deduplication, payload size limits, protection against reprocessing, never a
  direct LLM call from a webhook controller.
- **Agent runtime:** bounded execution, tool allowlist, tool result size limits, a limit on re-analyses, no
  unauthorized model choice, limits that prompt injection cannot change, protection against delegation loops between
  agents.
- **AI kill switches:** a global, a per-store and a per-automation stop of new paid model calls. They are independent
  safeguards: any one of them on stops the call, and turning one off never turns another off. They work independently
  of the kill switch for external business mutations (ADR-0020), and they do not stop deterministic store monitoring.
  Changing a stop is an explicit, audited action; the runtime cannot change it. Hard budget limits are not a kill
  switch and do not depend on one: an exhausted budget always refuses the call (fail closed). The MVP has no automatic
  latching of a kill switch on cost; it may be added later for anomaly detection.

## 13. Budget exhaustion and graceful degradation

Exhausting the budget does not stop the whole application.

| Process | Behaviour after the AI budget is exhausted |
|---|---|
| Integration Health | Deterministic monitoring |
| Inventory Guardian | Baseline and alerts |
| Order Watchdog | Rules and alerts |
| Profit Monitor | Metrics, no new LLM analysis |
| Ad Spend Guardian | Rule-based monitoring |
| Daily Briefing | Template report |
| Customer Support | Safe handoff to a person |

A required model decision is never replaced by an arbitrary or unverified deterministic decision. When the fallback
does not meet a process's safety criteria, the system moves to `INSUFFICIENT_DATA`, `MONITOR` or escalation.

A store's budget may be split into reserves for individual automations, in particular operationally important ones. A
customer cannot exceed the limit of their plan. The MVP has no automatic paid top-ups.

## 14. Cost monitoring

Metrics: `ai_cost_total`, `ai_cost_per_store`, `ai_cost_per_automation`, `ai_cost_per_model`,
`ai_tokens_input_total`, `ai_tokens_output_total`, `ai_run_cost`, `ai_budget_utilization`, `ai_budget_denials_total`,
`ai_unknown_settlements`, `ai_model_retry_cost`, `ai_cache_hit_rate`, `ai_provider_throttling_total`. Names may follow
the project's convention (`operator_` prefix, ADR-0024). Store ids are never high-cardinality Prometheus labels; per
store detail comes from the usage ledger.

Alerts: by default 70%, 90% and 100% (exhausted) of a budget, as a versioned and configurable parameter set; each
threshold notifies once per budget period, an unusual cost increase, too many retries, a growing number of
uncertain settlements, price list read errors, provider overload, a malfunctioning budget governor.

A basic AI cost view for the platform administrator. Store users never see the platform's detailed economics.

## 15. Security and tenancy

Every operation on a store's budget is bound to a trusted `TrustedStoreScope`, including autonomous Temporal and agent
runtime operations.

- An organization cannot spend another organization's budget.
- A store cannot change another store's budget.
- The LLM cannot give or change a budget scope.
- An agent cannot raise a limit.
- Only an authorized platform operator changes global policies.
- A store owner cannot raise a plan-derived policy above the plan's allocation.

Limit changes are versioned and audited. Row-level security and database privileges follow ADR-0022. For system
processes the backend rebuilds the scope from trusted records, never from model output.

## 16. Tests and acceptance criteria

### A. Budget enforcement

1. A call without a reservation never reaches the provider.
2. An exhausted store budget blocks a new call.
3. An exhausted organization budget blocks its stores.
4. An exhausted platform budget blocks every tenant.
5. Parallel reservations cannot overspend through a race.
6. An idempotent repeat does not charge the budget twice.
7. A stricter policy blocks the next call.
8. The budget resets correctly at the period boundary.

### B. Usage accounting

9. Settlement records the correct cost and tokens.
10. A double settlement does not create a double cost.
11. A timeout before the request is sent can release the reservation safely.
12. A timeout after a possibly executed call moves to `UNKNOWN`.
13. `UNKNOWN` never releases the budget automatically.
14. A new price list version does not change historical call costs.
15. A cost above the estimate is detected and settled explicitly.

### C. Agent runtime

16. An agent cannot exceed its call limit.
17. Retries count towards the run budget.
18. An agent cannot choose a model that is not allowed.
19. A tool cannot call an LLM provider around the `GovernedModelGateway`.
20. Exceeding the budget ends the run in a controlled state.
21. Prompt injection cannot change the limits.

### D. Abuse and multi-tenancy

22. A duplicated webhook does not start two paid analyses.
23. Two stores cannot use each other's budgets.
24. One store cannot take the whole platform's concurrency pool.
25. An unauthorized user cannot raise a budget.
26. The global AI stop blocks new paid calls.
27. Deterministic processes keep running after the AI budget is exhausted.

### E. Reliability

28. A crash after the reservation does not lose the record.
29. A crash during the call does not lead to an unconditional free retry.
30. Reconciliation restores the correct ledger state.
31. A Temporal worker restart does not bypass the limits.
32. A failure of the budget governor itself fails closed for new paid operations.

Unit and integration tests use a real PostgreSQL (Testcontainers), concurrency, Temporal and a fake model gateway. CI
needs no paid calls to real models.

## 17. MVP scope

Required before AI runs for customers: hierarchical budget limits, atomic cost reservation, the usage ledger, cost
settlement, `UNKNOWN` handling, per-run limits, the global AI kill switch, tenant isolation, basic concurrency limits,
event deduplication, a basic price catalog, safe fallback, cost alerts.

Possible later: advanced automatic model routing, better cost prediction, AI credits and paid top-ups, dynamic
subscription pricing, optimization by cost per achieved outcome, per-agent profitability analysis, extra rate limiting
infrastructure at larger scale.

## 18. Alternatives considered

- **A. Limits only at the LLM provider.** Rejected: provider limits know nothing about subscription economics, cost
  per store or internal automations.
- **B. Counting tokens after the call.** Rejected as the only mechanism: it does not prevent parallel overspending.
- **C. Redis as a required component.** Rejected for the MVP: PostgreSQL provides durable reservations, limits and
  the ledger at the expected load.
- **D. A separate AI billing microservice.** Rejected: unnecessary operational complexity.
- **E. Prepaid AI credits.** Deferred: internal budgets tied to the subscription plan are enough for the MVP.
- **F. Unlimited AI within the subscription.** Rejected: no predictable SaaS profitability.

## 19. Consequences

Positive: a predictable maximum cost of admitted work, lower risk of runaway agent loops, protection of other tenants'
budgets, subscription profitability can be calculated, provider-agnostic governance, auditable model costs, a
controlled growth in the number of autonomous agents.

Negative: extra database work before every model call, more complex retry and timeout handling, the cost of
maintaining the ledger and reconciliation, price lists need updates, possible delays under concurrency limits, possible
refusals caused by conservative estimates.

**Limit of the guarantee.** The system controls the admission of paid operations strongly, but it cannot guarantee
that the estimates match the provider's invoice exactly: unknown call outcomes, price changes, extra charges and
imperfect token estimates cause differences. Budgets therefore keep a safety margin and spending is reconciled with
provider billing.

## 20. Open questions

- O-01: target monthly AI budgets per SaaS plan.
- O-02: default reserves for critical processes, for example Customer Support.
- O-03: source and schedule of price list updates and verification.
- O-04: limit parameters for specific automations after cost evaluation.
- O-05: USD/PLN conversion rules and exchange rate buffer.
- O-06: level of detail of the platform cost view.
- O-07: reconciliation procedure with Anthropic and Bedrock billing reports.
- O-08: retention of the financial ledger and usage data.
- O-09: policy for `UNKNOWN` reservations that stay unsettled for a long time.

None of them blocks this decision or stage 2 implementation. O-05 is answered: USD is the technical currency and the
interface is multi-currency (section 3.2). O-01, O-02, O-04, O-06 are product settings answered before customers;
O-03, O-07, O-08 and O-09 must be closed before paid AI operations run for customers. (They are
numbered within this ADR; they are not the plan's O-xx questions.)

## 21. Implementation plan

- **Stage 1, cost safety foundation:** platform and store budgets, pricing catalog, atomic reservations, usage
  ledger, `GovernedModelGateway`, run limits, concurrency and failure tests.
- **Stage 2, runtime protection:** provider rate limits (RPM, TPM, parallel calls), admission priorities with reserved
  slots and bounded waiting, API and trigger rate limits with idempotency and event deduplication, `UNKNOWN`
  reconciliation, per-store and per-automation kill switches, cost alerts, a single price source and the platform
  independence guard. The basic admission control, the per-store and global concurrency limits, the global AI kill
  switch and the analysis cooldown were delivered with stage 1 (E-53 step 5).
- **Stage 3, cost optimization:** model routing, context optimization, prompt caching, cost monitoring per
  automation, basic administrative cost view.

Stages 1 and 2 are finished before the first paid AI use by customers. Stage 3 is delivered incrementally, based on
measured costs and evaluation results.

## 22. Relation to ADR-0016 and the current implementation

Reviewed on 2026-11-15 against the `pilot` branch. ADR-0016 stays in force; this ADR extends it and changes the points
below.

### Already in place (kept and reused)

- **Per-run limits.** `RunLimits` (model calls, tool calls, wall time, estimated input per request, total output
  tokens, estimated cost in USD, output repair calls) ends every limit with an explicit status (`LIMIT_EXCEEDED` and a
  stop reason). The limits are set by the backend; the model never sees or changes them.
- **Per-call reservation inside a run.** Before every call the runtime estimates the input conservatively (3
  characters per token, system text, tools and history included), reserves the cost of that input plus the call's
  output limit at the price list, and refuses the call when the run's cost limit would be exceeded. After the call it
  settles the reported usage; missing usage is settled at the estimate and the run is flagged `usage_complete=false`.
- **Shared cost across attempts.** All attempts of one analysis (primary and fallback model) share one cost limit
  through `analysis_key` and `costSpentBefore`; a run left `RUNNING` by a lost worker counts as a used attempt. This
  matches section 5 ("retries use the shared run budget") and section 7 ("retry protection").
- **No hidden retries.** The Anthropic client runs with `max-retries: 0` and `maxRetries(0)` per request, so every
  provider call is a call the runtime counted.
- **Fail-closed pricing.** A model without a price ends the run with `MODEL_NOT_PRICED`; the price list carries a
  version recorded on every run (`agent_run.price_list`).
- **Backend-chosen model.** Models come from `operator.agent.models`; nothing from a request or the model chooses one.
- **Off by default.** `operator.agent.enabled=false` keeps the deterministic baseline as the only proposer; the
  baseline already gives the graceful degradation of section 13 for the Inventory Guardian.
- **Tenancy.** `agent_run` and its steps are store tables with a composite FK and forced RLS (ADR-0022).
- **Metrics.** `operator_agent_runs_total` and `operator_agent_cost_usd` exist with an alert on failed runs
  (ADR-0024).

### Gaps and conflicts the implementation must close

1. **Timeouts are not treated as possibly charged.** Today a `ModelFailure` (including `TIMEOUT` and `UNKNOWN`) ends
   the run as `MODEL_FAILED` without adding any cost, and the next attempt starts with `costSpentBefore` that ignores
   it. Section 4.5 requires a failure after the request may have been sent to keep the reservation as `UNKNOWN`
   (counted against the run, store and platform budgets). Only a failure known to happen before sending (deadline
   passed, no model configured, provider `REJECTED` before processing) may release it.
2. **Reservations are held in memory.** The run's cost is written only when the run finishes; a crash mid-run leaves
   the spent cost only in the step rows. Section 4.3 needs a persisted reservation per model call, written before the
   provider call, with an idempotency key `(agent_run_id, call sequence)`.
3. **No budgets above the run.** There are no platform, organization, store or automation budgets and no periods.
   Section 3 is new work.
4. **The provider adapter is reachable.** `com.ecommerceagent.intelligence.model` is a Spring Modulith named
   interface and `SpringAiAnthropicGateway` and `ModelGatewayRouter` are public. Today only the runtime uses them, but
   nothing enforces it. The governed gateway becomes the only exported entry; provider adapters move to an internal
   package, and an architecture test asserts that no class outside the governance path depends on them.
5. **Limits are code, not versioned policy.** `InventoryAgent` defines its `RunLimits` in code. Section 5 asks for a
   versioned policy recorded on the run. We follow ADR-0026: a versioned parameter set with a pinned hash test, the
   version stored on `agent_run`, and the cloud configuration check refusing an overridden set that keeps the default
   version.
6. **Usage and prices lack cache and region fields.** `ModelUsage` carries input and output tokens only and
   `PriceList.Price` has input and output prices only. Prompt caching (section 8) must not be enabled before the
   usage record carries cache write and read tokens and the price list carries their prices, and before the price
   entry carries the region or inference profile (Bedrock prices differ per region).
7. **The price list is configuration.** `operator.agent.price-list` has a version but no effective date, source or
   verification date. Stage 1 moves it to the `ai_pricing_catalog` table (or a versioned file loaded into it) and
   refuses calls when the entry is unverified or stale.
8. **No concurrency limit per store.** The number of parallel agent runs is bounded only by Temporal worker
   concurrency. Section 6.2 needs a per-store and global admission limit shared by all instances.
9. **No runtime AI kill switch.** `operator.agent.enabled` needs a restart. Section 12 needs a switch that takes
   effect before the next model call, separate from `operator.execution.mutations-enabled` and the per-store mutation
   stop of ADR-0020.
10. **Cooldown.** The analysis key is `(opportunity, snapshot)`, so a new snapshot of the same opportunity starts a
    new paid analysis. A cooldown with material change detection (section 7) is new work.
11. **Naming.** `ModelUsage` already exists (tokens reported by the provider). The governor's settlement takes that
    type and adds the cost; the run id stays a `UUID` as in `AgentRun` rather than a new `AgentRunId` type, unless a
    later change introduces typed ids everywhere.
12. **Tables without a store.** Platform and organization budgets have no `store_id`, while the RLS policies of
    ADR-0022 are store policies. Those tables are written only by the governor under the system role with explicit
    rules, and are read by store users only through store-level views.
13. **Migration numbers.** The new tables use the next free versions on `pilot` (V30 onwards).

### Implementation status

**Stage 1, step 1 (V30, module `aigovernance`): budgets, pricing catalog and reservations.**

- Tables `ai_price`, `ai_budget_policy`, `ai_budget_balance`, `ai_budget_reservation` and `ai_budget_charge`.
  Amounts are `numeric(20, 8)`, never floating point; every window is in UTC.
- `ai_price` is append-only: a new price is a new `price_version`, and only `verified_at` may be set later. A
  price is used only when it is verified, in effect, the newest version for (provider, model, region) and checked
  within `operator.ai-governance.price-max-verification-age` (90 days). The prices copied from the old price list
  are seeded unverified, so no paid call can be reserved until someone checks them against the provider.
- `ai_budget_policy` rows are versioned and immutable; a change supersedes the active row and inserts the next
  version. Platform and store budgets are required: without them a call is refused (`NO_PLATFORM_BUDGET`,
  `NO_STORE_BUDGET`). Organization and automation budgets apply when they exist. Periods are `MONTHLY` and
  `DAILY`.
- Balances are kept per scope and period window, not per policy version, so a mid-period policy change keeps what
  was already reserved and spent.
- The reservation is one call of the `SECURITY DEFINER` function `ai_reserve`. It takes the store from the session
  settings, locks every applicable balance in a fixed order (level, period, scope), checks every limit and the
  currency, and writes the reservation and its charges, all in one statement. The idempotency key is unique per
  store; a repeated key returns the first reservation and charges nothing. `ai_release` returns the budget only
  for a reservation still in `RESERVED`, that is, a call known not to have been sent.
- The estimate is conservative: estimated input tokens plus `input-token-margin` (25%), the input price raised to
  the cache-write price when caching is requested, the full maximum output, priced extra units, rounded up. A
  feature without a price (cache, extras) is refused, never treated as free.
- The runtime role reads only its store's reservations and the price catalog. It cannot read or write budget
  policies, balances or charges, so a store never sees platform economics. Prices and policies are changed by
  reviewed migrations (`operator_migrator`) until an administrative interface exists.

**Stage 1, step 2 (V31): settlement, uncertain outcomes and the usage ledger.**

- `ai_mark_sent` must be called and committed before a request leaves. It lets exactly one caller move a
  reservation from `RESERVED` to `IN_PROGRESS`; an expired, released or already attempted reservation is never sent.
  This is the line between "known not sent" and "possibly sent": a worker that dies before it leaves `RESERVED`, one
  that dies after it leaves `IN_PROGRESS`.
- `ai_settle` records the provider's usage for an `IN_PROGRESS` or `UNKNOWN` reservation. The cost is computed in the
  database from the reservation's own price (input, output, cache write, cache read, priced extra units, rounded up
  to 8 decimals), so the caller cannot understate it. Unused reservation goes back to every charged balance; the
  actual cost is charged in full, and an overrun is stored on the reservation and the ledger row, never capped.
  Cache reads without a cache-read price are charged at the input price; cache writes or extra units without a price
  are refused, so the reservation stays open and is reconciled rather than recorded as free.
- `ai_mark_unknown` marks a sent call whose result is unknown (timeout, dropped connection, unreadable answer). The
  reservation keeps counting. A retry of the same command gets the same reservation back, and `ai_mark_sent` answers
  `ALREADY_ATTEMPTED`: an ambiguous call is not sent again without a new reservation.
- `ai_reconcile` closes an `UNKNOWN` reservation as `NOT_CHARGED`, `CHARGED` (a confirmed amount) or `CONSERVATIVE`
  (the full reserved amount), recorded with the actor. It is not exposed to store users.
- `ai_sweep_reservations`, run by the worker every minute: expired `RESERVED` → `RELEASED`, expired `IN_PROGRESS` →
  `UNKNOWN`, and `UNKNOWN` older than `unknown-conservative-settlement-after` (24 hours) → `RECONCILED`
  conservatively. With `unknown-conservative-settlement-enabled=false` an `UNKNOWN` waits for a person. Either way it
  is visible: gauges `operator.ai.reservations.unknown`, `...unknown.oldest.age`, `...overdue` and
  `operator.ai.overruns.recent`, with alerts and runbook entries.
- `ai_usage_event` is the usage ledger: one append-only row per closed reservation that may have cost money, with
  provider, model, region, price version, token counts, cost, reserved amount and overrun. No prompt or response
  content. A store reads only its own rows; only the functions write them.
- Every transition is a conditional update of one locked row; nothing assumes exactly-once delivery. Settling the
  same usage again returns the first settlement; different usage for a closed reservation is a conflict.
- Not covered yet: a correction after a conservative reconciliation when provider data arrives later (it is a
  conflict today and needs an adjustment entry), and the retention period of `ai_usage_event` (O-03).

**Stage 1, step 3: `GovernedModelGateway` and the enforced boundary.**

- `GovernedModelGateway` (package `intelligence.model`) is the only path from the agent runtime to a provider. For
  each model call it refuses what cannot be sent before reserving anything (deadline passed, provider not
  configured, a surrounding database transaction that would keep the sent mark uncommitted), reserves the
  conservative maximum with the idempotency key `agent-run:<run id>:call:<n>`, marks it sent, calls the provider,
  and then settles the reported usage. A failure the adapter reports as an error response the provider does not
  bill (`ModelFailure.confirmedNotCharged`, set by the Anthropic adapter for HTTP error responses) is settled at
  zero; any other failure after sending, and an answer without usage, leaves the reservation `UNKNOWN`. When the
  settlement itself fails, the answer is still used and the reservation is left to the sweep.
- Gap 1 is closed: a possibly charged failure now counts against the run at its reserved maximum (tokens and cost),
  so the next attempt starts with it in `costSpentBefore`. A call governance does not admit ends the run with
  `AI_GOVERNANCE:<reason>`, `LIMIT_EXCEEDED` for budgets and `MODEL_FAILED` otherwise, and is never sent.
- Gap 4 is closed: the provider adapters (`SpringAiAnthropicGateway`, `ProviderGateways`, which replaces
  `ModelGatewayRouter`) moved to the internal package `intelligence.model.provider`, outside the `model` named
  interface. `GovernedModelBoundaryTest` (ArchUnit, on the compiled application classes) fails the build when any
  other class depends on that package, calls `ModelGateway.complete`, or uses a model SDK (Spring AI chat, Spring AI
  Anthropic, the Anthropic SDK); the module rules of Spring Modulith keep other modules out of `intelligence`
  internals. A deliberate bypass was checked to fail the test.
- The runtime's in-run limits (`RunLimits`, the configured price list for the run's estimate) stay as they are until
  step 4; the budgets, the catalog price and the ledger now apply to every call on top of them.
- Not yet: run limits as versioned policies (step 4). The agent stays off on the pilot until stage 1 is complete,
  and prices must be verified and budgets set before it can be switched on.

**Stage 1, step 4 (V32): run limits as versioned policies.**

- Gap 5 is closed. The limits of an Inventory Agent run are the parameter set `inventory-agent-run`
  (`InventoryAgentRunPolicy`, `operator.agent.run-policies.inventory-agent`), version `inventory-agent-run/1` with
  the values that were code before: 5 model calls, 8 tool calls, 60 s, 12,000 estimated input tokens per call,
  1,500 output tokens per call (moved from `operator.agent.max-output-tokens-per-call`), 4,000 output tokens per
  run, USD 0.10 per analysis, 1 repair call, and at most 2 attempts along the model policy. Money is a
  `BigDecimal`.
- It follows ADR-0026 point 6: `ParameterSetVersionTest` pins a hash of the defaults to the version, and the cloud
  profile refuses an override that keeps the default version, and refuses the old output key, which would otherwise
  be ignored silently. An inconsistent set (a per-call output limit above the run limit, a zero cost or wall time,
  a malformed version) stops the start-up.
- `AgentDefinition` no longer carries limits; `AgentRunRequest` carries the `RunLimits` of the policy, and every
  `agent_run` records `run_policy` and `run_limits` (V32), next to the price list. Runs started before V32 have both
  empty.
- Tightening a policy: the policy is read at start-up, so a change applies from the next start. A run lasts at most
  `maxRunDuration` and a run lost by a stopped worker counts as a used attempt, so no run continues under an older
  policy after a restart. The global AI stop that applies before the next call without a restart is gap 9.
- Not in this step: the concurrency admission (gap 8), the runtime AI stop (gap 9) and the cooldown (gap 10), done in
  step 5. The run's own estimate still uses the configured price list, while the reservation uses the catalog price
  (`ai_price`); both must be verified together.

**Stage 1, step 5 (V33): admission, the platform AI stop and the cooldown.**

- Gap 8 is closed. Every agent run holds a lease (`ai_run_lease`) from admission to its end; `ai_acquire_run_lease`
  admits it only while the store has fewer active leases than `maxConcurrentRunsPerStore` of the run policy (1) and
  the platform fewer than `operator.ai-governance.max-concurrent-runs` (8). One transaction-scoped advisory lock
  serialises admissions across all instances; a lease expires one minute after the run's wall time, so a lost worker
  frees its slot. A run that is not admitted ends `LIMIT_EXCEEDED` with `AI_GOVERNANCE:STORE_LIMIT` or
  `GLOBAL_LIMIT` before anything is reserved, and the fallback model is not tried. The store limit keeps one store
  from taking the platform pool (criterion 24). Not yet: provider RPM/TPM, priorities and queue length (section 6.2);
  at pilot scale the per-run limits and the concurrency bound them.
- Gap 9 is closed. `ai_platform_stop_change` is an append-only log of stop decisions written by operations with the
  administration role; the runtime cannot change it. The database enforces it: no run is admitted while it is on, and
  `ai_mark_sent` refuses a call reserved before the stop and releases its reservation (`SendDecision.STOPPED`), so the
  stop applies to running runs before their next call and a granted reservation never allows a call after it
  (criterion 26). The run ends `STOPPED` with `AI_GOVERNANCE:AI_STOPPED`; the baseline proposes. The gauge
  `operator_ai_stopped` and the alert `AiStopped` make it visible; the runbook has the statements.
- Gap 10 is closed for the Inventory Guardian. Each run records a fingerprint of the situation it analysed
  (`agent_run.situation`: out of stock, the active ads, whether replenishment is known). A new snapshot of the same
  opportunity inside `cooldown` (60 minutes) after a run that may have cost money is not analysed again unless the
  fingerprint changed; the baseline proposes with the fallback reason `AGENT_COOLDOWN`. Reaching zero stock or an ad
  changing state is a material change.
- The run policy is version `inventory-agent-run/2` (the two new parameters); the hash of version 1 stays pinned for
  the runs that recorded it. The guardian now records why the agent was not used (`AGENT_COOLDOWN`,
  `AI_GOVERNANCE:<reason>`, `AGENT_OUTPUT_REJECTED`) instead of a generic `AGENT_FAILED`.
- With steps 1 to 5 the cost safety foundation of stage 1 is in place. The agent stays off
  (`operator.agent.enabled=false`) until prices are verified in `ai_price`, the platform and store budgets are set,
  and the provider terms for real data are confirmed.

### Rollout relative to the pilot

The agent stays off on the pilot until stage 1 is complete, because the pilot runs on the platform's money and the
platform budget, the persisted reservation and the `UNKNOWN` handling are what bound that spend. Stage 2 is complete
before any paying customer has the agent on. Section 13 rows for processes that do not exist yet (Order Watchdog,
Profit Monitor, Ad Spend Guardian, Daily Briefing, Customer Support) are requirements for those processes when they
are built.

---

**Final decision:** AI cost governance is a mandatory part of the safety and economics infrastructure of Autonomous
E-commerce Operator. It must protect the platform against excessive spending regardless of the model provider, user
actions and autonomous workflows.

## 23. Review against the stage 1 implementation (2026-11-15)

After E-53 the decisions were reviewed against the code and amended before acceptance:

- **A1.** Section 5: ordinary run limits hold until the run ends; budgets, kill switches, revoked authorization and
  governance safety blocks apply before every next paid call. Implemented: `GovernedModelGateway` re-checks the
  actor's access before each call (`AUTHORIZATION_REVOKED`, the run ends `STOPPED`); budgets are reserved per call;
  the global stop is checked when the call is marked sent. The per-store and per-automation stops follow in E-54.8.
- **A2.** Section 9: the pricing catalog `ai_price` becomes the only price source; the run cost estimate moves from
  configuration to the catalog in E-54.2. Until then the reservation, which always uses the catalog, is what decides.
- **A3.** Section 21: stage boundaries updated to what stage 1 delivered.
- **A4.** Section 10: platform independence.
- **A5.** Section 20: which open questions block what.

Also in this review: the failure classification of section 4.5 replaced the earlier "any 4xx or 529 is not charged"
rule with the explicit rule set `anthropic-charge/1`, with regression tests per status and error type, for errors
after a stream started and for calls that were never sent.

