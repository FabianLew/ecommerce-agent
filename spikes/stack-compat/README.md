# Spike: stack-compat (B-02, B-03)

A one-off experiment, not production code. It verifies two decisions:

- **B-02 / [ADR-0001](../../docs/adr/0001-stack-versions.md)** — Spring Boot 4.1.2, Spring AI 2.0.2,
  Spring Modulith 2.1.2 and Temporal 1.40.0 work together on Java 21 (Gradle 9.8.2).
- **B-03 / [ADR-0002](../../docs/adr/0002-spring-ai-tool-calling.md)** — tool calls come back to our runtime;
  Spring AI does not execute them.

## Running

```bash
cd spikes/stack-compat
./gradlew test
```

No API key, no network access to Anthropic and no Temporal server are needed: the tests use the in-process
Temporal test server and a local stub of the Messages API (`AnthropicStubServer`). The test build clears
`ANTHROPIC_BASE_URL` and `ANTHROPIC_API_KEY`, and the tests set the base URL explicitly.

## Contents

| Element | Purpose |
| --- | --- |
| `inventory`, `decisions`, `workflow` | Three minimal Spring Modulith modules (boundary verification) |
| `StackCompatibilityTest` | B-02: context, `AnthropicChatModel`, Temporal workflow, `ApplicationModules.verify()` |
| `ToolCallingBoundaryTest` | B-03: tool boundary, allowlist, contrast test with `ToolCallingAdvisor` |
| `AnthropicStubServer` | Stub of `/v1/messages` with a response queue and request recording |

Once the tool-boundary tests move to the backend (I2/I3), this directory can be removed.
