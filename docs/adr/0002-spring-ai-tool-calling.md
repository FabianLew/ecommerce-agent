# ADR-0002: The tool loop belongs to the runtime, not to Spring AI

- Status: Accepted (PR merge, 2026-11-14)
- Date: 2026-11-14
- Related: document 02 (sections 3–4: Agent Runtime, Tool Registry), document 03 (IG-11), backlog B-03,
  ADR-0001, `spikes/stack-compat`

## Context

The LLM only proposes. Every tool call must go through our runtime: the READ tool allowlist, argument
validation, `TrustedStoreScope`, step and token limits, audit (document 02). If a library executes a tool on its
own and calls the model again, those controls are bypassed and we lose the record of the run.

The plan assumed that `internalToolExecutionEnabled(false)` in the chat options would be enough. Spike B-03
showed that this option no longer exists in Spring AI 2.0.2 and that the tool execution model is different:

- `ChatModel.call()` (e.g. `AnthropicChatModel`) **does not execute tools**. It returns a response with
  `toolCalls`; there, `ToolCallingManager` is used only to build the tool definitions.
- The loop is run by `ToolCallingAdvisor` in `ChatClient`. Auto-configuration registers it automatically
  when the call has tools (`spring.ai.chat.client.tool-calling.enabled`, default `true`).
  It can also be enabled per call (`AdvisorParams.toolCallingAdvisorAutoRegister(true)`).

## Decision

1. The Agent Runtime talks to the model through **`ChatModel` directly** (behind our `ModelGateway`),
   not through `ChatClient`. The tool loop (model call → tool dispatch through our `ToolDispatcher` →
   `ToolResponseMessage` → next call) is runtime code.
2. Defense in depth: the application configuration always has `spring.ai.chat.client.tool-calling.enabled=false`,
   so that a `ChatClient` taken from the context does not execute tools either.
3. Only definitions of allowlisted READ tools are passed to the model. A call to a tool outside the allowlist
   ends with a structured `TOOL_NOT_ALLOWED` error sent back to the model, without execution.
4. A `ToolCallback` in the options is used only to describe the tool (name, description, input schema). Tool
   logic lives in the runtime, not in callbacks.
5. The API base URL is always set explicitly (`spring.ai.anthropic.base-url`), because the SDK reads
   `ANTHROPIC_BASE_URL` from the environment (ADR-0001).
6. Every Spring AI upgrade must pass the tool boundary tests (below); they belong to the runtime safety tests,
   which block the merge.

## Alternatives considered

- **`ChatClient` + `ToolCallingAdvisor` with our own `ToolCallingManager`** (injecting our controls into the
  Spring AI loop) — rejected: the loop, the limits and the run state would be spread between our code and the
  advisor chain; that is harder to audit and makes the order of controls harder to guarantee. We reconsider it
  only if a concrete need appears (e.g. streaming with tools).
- **The official Anthropic SDK directly, without Spring AI** — rejected for now: decision I2 is Spring AI;
  with `ChatModel` and without `ChatClient`, Spring AI gives us message mapping, observability and
  configuration without taking control of tools. The `ModelGateway` boundary lets us change this later.
- **Relying on the default behavior without tests** — rejected: the API already changed between 1.x and 2.0.

## Consequences

- Positive: one explicit tool loop in the runtime; every control and every step is in our code and in the
  ledger/audit.
- Negative: we write our own loop (a few dozen lines plus limits) instead of using a ready-made one.
- Risk: future Spring AI versions may move tool execution again. Mitigation: the tests below in the runtime
  safety suite (moved from the spike to the backend in I2/I3).
- `CLAUDE.md` refers to this ADR for how the rule is enforced.

## Verification

Spike `spikes/stack-compat`, `ToolCallingBoundaryTest`, 5/5. The tests use the auto-configured
`AnthropicChatModel` and a local Messages API stub (no key and no network):

- `toolCallIsReturnedToCallerAndNotExecutedBySpringAi` — `ChatModel.call()` returns `tool_use`,
  the callback is not executed, 1 request with the tool definition went to the API.
- `runtimeDrivesTheLoopAndSendsToolResultBack` — our dispatcher executes the tool, and a `tool_result`
  with the `tool_use_id` and evidence is sent in the second request; Spring AI executed 0 tools.
- `toolOutsideAllowlistIsNeverExecuted` — the model asks for `pause_ad` (IG-11): `TOOL_NOT_ALLOWED`, 0 executions.
- `autoConfiguredChatClientDoesNotExecuteTools` — with `tool-calling.enabled=false`, a `ChatClient` from the
  context returns `tool_use` without execution. Checked by mutation: after changing it to `true`, the test fails.
- `chatClientWithToolCallingAdvisorWouldRunToolsInsideSpringAi` — a contrast test documenting the
  threat: with `ToolCallingAdvisor`, one call executes the tool and makes the second request on its own.
