# ADR-0027: AI cost governance, budget enforcement and abuse protection

- Status: Proposed
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

Provider costs are stored in their original currency as `NUMERIC`, never `double` or `float`. In the MVP the technical
currency of AI cost accounting is USD. When a plan budget is in PLN, conversion uses a versioned exchange rate with a
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

Tightening an active safety or budget policy also applies to running runs, at the latest before their next model call.
A reservation already granted does not allow a call after a later global AI stop.

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
- **Platform AI kill switch:** a global way to stop new paid model calls. It works independently of the kill switch
  for external business mutations (ADR-0020), and it does not stop deterministic store monitoring.

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

Alerts: 70% and 90% of a budget, an exhausted budget, an unusual cost increase, too many retries, a growing number of
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

These do not block contracts and tests, but must be closed before paid AI operations run for customers. (They are
numbered within this ADR; they are not the plan's O-xx questions.)

## 21. Implementation plan

- **Stage 1, cost safety foundation:** platform and store budgets, pricing catalog, atomic reservations, usage
  ledger, `GovernedModelGateway`, run limits, concurrency and failure tests.
- **Stage 2, runtime protection:** admission control, concurrency limits, rate limiting, deduplication, `UNKNOWN`
  reconciliation, AI kill switch, cost alerts.
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
