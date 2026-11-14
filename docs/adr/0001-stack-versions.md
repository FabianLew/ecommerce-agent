# ADR-0001: Backend stack versions

- Status: Accepted (PR merge, 2026-11-14)
- Date: 2026-11-14
- Related: document 02 (AI architecture), backlog B-02, ADR-0002, `spikes/stack-compat`

## Context

The backend is a Spring Boot modular monolith with the `api` and `worker` profiles (document 02, decisions I2 and I4).
Before production code is written (I2+), we have to confirm that the chosen versions of Spring Boot, Spring AI,
Spring Modulith and the Temporal SDK work together: they resolve from one set of BOMs, start in one context and
can be tested without external infrastructure.

Constraints:

- We use GA releases only. RC/M versions do not enter the project.
- We do not adopt releases younger than about 2 weeks (risk of quick fixes and regressions).
- Build: Gradle Kotlin DSL (decision O1).

## Decision

| Component | Version | Notes |
| --- | --- | --- |
| Java (toolchain) | 21 (LTS) | See "Consequences": Java 25 is evaluated separately |
| Gradle (wrapper) | 9.8.2 | 9.9.x rejected as too recent |
| Spring Boot | 4.1.2 | Latest GA; 4.2 is in the RC phase |
| Spring AI | 2.0.2 | Latest GA of the 2.0 line; 2.1 is in the RC phase |
| Spring Modulith | 2.1.2 | Compatible with Boot 4.1 |
| Temporal Java SDK + `temporal-spring-boot-starter` | 1.40.0 | Works with Boot 4.1.2 (see below) |

Versions are managed by BOMs (`spring-boot-dependencies`, `spring-ai-bom`, `spring-modulith-bom`);
Temporal has an explicitly pinned version. The Anthropic client in Spring AI 2.0 is the official SDK
`com.anthropic:anthropic-java-core` (2.72.0 through the Spring AI BOM).

## Alternatives considered

- **Spring Boot 4.2 RC / Spring AI 2.1 RC** — rejected: pre-release versions.
- **Spring Boot 3.5 + Spring AI 1.x** — rejected: starting a new project on a line that loses support sooner
  means a certain migration during the MVP; Spring AI 1.x has a different tool execution model than 2.0
  (ADR-0002), so the migration would touch the most sensitive part of the runtime.
- **Java 25 (LTS)** — not rejected on the merits; in the environment where the spike was built, JDK 25 was not
  available for download, so we could not verify it. The decision stays open (see below).
- **Gradle 9.9.x** — rejected for now: a release younger than 2 weeks.

## Consequences

- Positive: one consistent set of GA versions, testable locally without network access (Temporal in-process test
  server, Anthropic API stub).
- `temporal-spring-boot-starter` 1.40.0 declares an import of the Spring Boot 2.7.18 BOM in its POM. In Gradle,
  with the Boot 4.1.2 platform, dependencies resolve to the Boot 4.1.2 versions and the starter works (the test
  passes), but this is a risk during upgrades: after every Temporal or Boot version change we run the
  spike/worker integration tests.
- Spring AI 2.0 changed the tool execution API compared with 1.x (`internalToolExecutionEnabled` was removed);
  ADR-0002 describes the consequences.
- The official Anthropic SDK reads the `ANTHROPIC_BASE_URL` environment variable. Every configuration
  (tests, environments) must set `spring.ai.anthropic.base-url` explicitly; the test build clears
  `ANTHROPIC_BASE_URL` and `ANTHROPIC_API_KEY`.
- To do: evaluate Java 25 in CI (a separate task that does not block I2). Changing the toolchain is one line,
  so the decision is easy to reverse.
- To do: set up Renovate/Dependabot with a "GA, at least 14 days old" rule when the backend is created (I2).

## Verification

Spike `spikes/stack-compat`, `./gradlew test` (Java 21.0.12, Gradle 9.8.2) — `StackCompatibilityTest`, 4/4:

- `springAiAnthropicChatModelIsAutoConfigured` — the context starts, `ChatModel` is `AnthropicChatModel`.
- `temporalWorkflowRunsThroughAutoDiscoveredWorkerAndActivity` — the workflow and the activity are discovered
  automatically and executed on the in-process Temporal test server.
- `springModulithVerifiesModuleBoundaries` — `ApplicationModules.verify()` passes for three modules.
- `crossModuleBeanWiringWorks` — injection across modules through the module's public API.
