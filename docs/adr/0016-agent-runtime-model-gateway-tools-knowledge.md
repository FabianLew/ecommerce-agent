# ADR-0016: Agent runtime, ModelGateway, read tools, knowledge registry and the agent proposer

- Status: Proposed
- Date: 2026-11-15
- Related: document 02 §3–8 and §12, document 03 §4–6 and §12 (IG-07, IG-11, IG-22, IG-24, IG-28), ADR-0002 (the tool
  loop belongs to the runtime), ADR-0011 (evidence, snapshot), ADR-0012 (DecisionProposal v1, baseline, workflow),
  backlog B-26..B-30

## Context

Until now the Inventory Guardian proposes with the deterministic baseline only. F2 adds the agent: a model reads the
frozen context through read tools and returns a recommendation under the same contract. The architecture fixes the
boundaries (the model only proposes, sees READ tools only, never supplies the tenant; our runtime owns the loop and
the limits; every run pins its versions). What is left to decide is how this is built in the backend and how it
enters the existing episode workflow without weakening it.

## Decision

1. **ModelGateway port** (`intelligence.model`). `ModelTurn complete(ModelRequest)` and `ModelCapabilities
   capabilities(ModelId)`. A request carries the trusted run id, the model, a system text, ordered canonical messages
   (user, assistant with tool calls, tool results), tool definitions, the output token limit of this call and a
   deadline. A turn carries the final text or complete tool calls, a finish reason (`COMPLETE`, `TOOL_USE`,
   `MAX_TOKENS`, `REFUSAL`, `OTHER`), usage (input and output tokens, or unknown) and the provider request id.
   Failures are a `ModelFailure` with a type (`TIMEOUT`, `RATE_LIMITED`, `UNAVAILABLE`, `REJECTED`, `UNKNOWN`), a
   retryable flag and the provider request id; no prompt text or secrets.
2. **Spring AI adapter.** `SpringAiAnthropicGateway` calls `ChatModel.call(Prompt)` directly (ADR-0002). Tools are
   declared through callbacks whose `call` always throws: Spring AI sends the definitions to the model and returns
   the calls, and if anything ever tried to execute one inside Spring AI it would fail loudly. Spring AI retries are
   off (`max-retries: 0`): retries and fallback belong to the runtime. The provider's own assistant message is kept
   with the canonical message for the rest of the same attempt (so provider-specific blocks such as thinking go back
   unchanged) and is never stored or carried to another model.
3. **Tool registry and dispatcher** (`intelligence.agent`). A tool is `AgentTool` with a `ToolDefinition` (name,
   version, description, JSON input schema with `additionalProperties: false`, effect `READ`/`PROPOSE`/`MUTATE`,
   result size limit). The registry gives a run the intersection of the agent definition's allowed tools, the skill's
   required tools and the `READ` effect. The dispatcher looks the name up in that set only (a name outside it is
   `TOOL_NOT_ALLOWED` and nothing runs, IG-11), validates the arguments against the schema (`INVALID_ARGUMENTS`),
   serves an identical repeated call from the run's cache, and runs the tool with a `ToolExecutionContext` built by
   the backend: the `TrustedStoreScope`, the run id and the frozen snapshot. Tool errors use the document's codes
   (`NOT_FOUND`, `FORBIDDEN`, `STALE_DATA`, `RATE_LIMITED`, `UNAVAILABLE`) and go back to the model as error results.
4. **The five Inventory Guardian read tools read the frozen snapshot**, not live tables: `get_inventory_forecast`,
   `get_replenishment_plan`, `get_product_ad_mappings`, `get_ad_delivery_state`, `get_recent_actions`. Every result
   carries the snapshot id, `asOf`, quality and the evidence references the proposal may cite. A variant or ad that is
   not in the snapshot is `NOT_FOUND`, whatever exists elsewhere (IG-07): the model cannot reach another store's or
   another episode's data even with a valid id. `get_recent_actions` adds the earlier proposals of the episode (and
   executed actions once B-38 exists) read through the scope. The snapshot is the single consistent state the
   proposal is validated against, so the agent and the baseline see the same facts.
5. **Agent runtime.** `AgentRuntime.run` drives the loop: `BeforeAgentRun` checks, then up to `maxModelCalls` model
   steps; for each tool call the dispatcher; tool results go back in one message. Limits apply to the run:
   `maxModelCalls`, `maxToolCalls`, `maxWallTimeSeconds`, `maxOutputTokensTotal`, `maxOutputRepairCalls`, and
   `maxEstimatedCostUsd`, which is shared by all attempts of one analysis. `maxInputTokens` limits the estimated size
   of one request (the context). Before each call the runtime reserves the cost of the estimated input and the call's
   output limit at the versioned price list, and settles the reported usage after it; a model without a price is not
   used. A final answer must be one JSON object (a Markdown code fence around it is tolerated); a truncated or
   unparsable answer gets at most one repair request without tools (IG-22). Every stop is an explicit status:
   `SUCCEEDED`, `LIMIT_EXCEEDED` (with the limit), `OUTPUT_INVALID`, `MODEL_FAILED`, `REFUSED`, and later
   `OUTPUT_REJECTED` when the validator rejects the proposal. Hooks are deterministic code (`AgentHook` with the
   before/after model and tool call points); a hook can stop the call or the run.
6. **AgentRun record** (migration V15, `agent_run` and `agent_run_step`). A run stores the analysis key, attempt,
   agent name and version, skill reference, the hashes of the knowledge package, the prompt, the tool set and the
   output schema version, the model policy, provider and model, the price list version, the snapshot, counters,
   usage (with an incomplete flag), the estimated cost, the status and stop reason, and the parsed output. Each model
   call and tool call is a step with its canonical request and result (tool arguments and results, the model's
   text and tool calls; no system prompt, which is pinned by hash). This is enough to replay a run on the same
   snapshot with a fake gateway (IG-24). Tables are store-scoped with the composite FK.
7. **Knowledge registry.** Approved packages live in `knowledge/skills/<name>/` (`SKILL.md`, `manifest.yaml`,
   `references/`, `examples/`, `evals/cases.jsonl`) and prompts in `knowledge/prompts/`, are packaged into the
   application and loaded at start. The registry computes a SHA-256 over the sorted file paths and contents of a
   package; the run pins it. The manifest names the version, the output schema version and the required read tools;
   a manifest that needs a tool the agent does not have stops the application from starting. Instruction files are
   text only. The first package is `inventory-guardian@1.0.0`.
8. **Agent proposer in the workflow.** A new activity `proposeAndPersist(opportunityId, snapshotId)` replaces the
   pair `propose` + `validateAndPersist` for new executions (`Workflow.getVersion`, so running and recorded episodes
   replay on the old path). The analysis key is `opportunityId:snapshotId`. The activity is idempotent: a stored
   proposal for the snapshot is returned as it is; a successful agent run for the key is reused without calling
   the model again. Attempts follow the model policy (primary, then fallback model), each a new `agent_run` on the
   same snapshot with the shared cost limit (IG-28). The agent's output is submitted exactly as returned through
   `DecisionProposals.submit` (schema, domain and evidence validation). If no attempt succeeds, or the validator
   rejects the agent's proposal, the baseline's proposal is submitted instead, and the run records why. A fallback
   never overwrites an accepted proposal.
9. **Switched off by default.** `operator.agent.enabled=false` keeps the baseline as the only proposer (local, CI, and
   every environment without an approved model key). The default policy `INVENTORY_BALANCED` uses `claude-opus-5-5`
   with effort `low`, falling back to `claude-sonnet-5-5`; both are configuration, and the choice is confirmed by the
   quality report (B-36), not by this ADR.

## Alternatives considered

- **ChatClient with tool callbacks that run our tools.** Rejected by ADR-0002: Spring AI would own the loop and the
  second model request.
- **Tools reading live tables.** More current, but the proposal would rely on facts outside its snapshot, which the
  validator rejects (IG-10), and a replay could not reproduce the run. A newer observation leads to a new analysis
  through the workflow's signals instead.
- **A generic agent framework or YAML-defined agents with their own permissions.** Not needed for one profile; the
  agent definition is code, so permissions cannot be widened by editing a file.
- **Failing the episode when the agent fails.** The baseline is the reference and the safe fallback (ADR-0012 point
  4); the episode must not stall because a provider is down.

## Consequences

- The agent path needs an approved API key, a data-processing review (EU data residency is still to be verified,
  plan decision I2) and the quality report before `operator.agent.enabled` is switched on anywhere.
- The price list is configuration with a version; it must be checked against the provider before the agent is used,
  and changing it needs a new version string.
- Opus-class models think adaptively and thinking counts as output, so the run limits of document 02 may be tight;
  the quality report measures stop reasons and cost, and the limits are changed only with a new ADR revision.
- Two activity paths exist in the workflow until old episodes have finished.

## Verification

- Gateway contract tests against a local stub of the Messages API: tool calls are returned and never executed
  inside Spring AI, the tool result goes back with its id, usage and finish reason are mapped, failures are typed.
- Runtime safety suite on a fake gateway, merge-blocking (B-36): IG-07, IG-11, IG-22, IG-24, IG-28, every limit, the
  hooks, idempotency of the activity and the fallback to the baseline.
- `InventoryGuardianWorkflowTest` runs the scenario with the agent switched off (baseline) and on (fake gateway), and
  replays the recorded histories of the old path.
