# ADR-0003: Backend module convention

- Status: Accepted (PR merge, 2026-11-14)
- Date: 2026-11-14
- Related: architecture plan (B2 "Proposed module map", D1), document 02, ADR-0001, backlog B-04

## Context

The backend is a modular monolith: one artifact run with the `api` and `worker` profiles. The module boundaries
from the B2 map must be checked automatically in every build, before domain code is written. Otherwise
dependencies start pointing upward (e.g. `identity` → `commerce`), and that is hard to reverse later.

## Decision

1. Module = a direct subpackage of `com.ecommerceagent` (the Spring Modulith convention). Modules:
   `identity`, `integration`, `commerce`, `businessprofile`, `metrics`, `decisions`, `support`,
   `reporting`, `intelligence`, `automation`, `web`.
2. Every module has a `package-info.java` with `@ApplicationModule(allowedDependencies = …)` following B2.
   The list of allowed dependencies is explicit, even when empty (`identity`). Changing the list is an
   architecture change and needs a justification in the PR.
3. A module's public API is the types in its base package. Subpackages (e.g. `commerce.internal`) are
   internal and not accessible to other modules (the Spring Modulith default rule).
4. Communication "up" the map (e.g. `commerce` informing `decisions`) happens only through domain events,
   not through calls.
5. `ModularityTest` (`ApplicationModules.verify()` + the list of expected modules) is part of
   `./gradlew check` and blocks the merge.
6. One Gradle project (`backend/`), without Gradle subprojects: Modulith guards the boundaries,
   and we split the build only when a concrete need appears (e.g. compile time).

## Alternatives considered

- **Gradle subprojects per module** — rejected for now: hard compile boundaries, but 11 build files
  and an extensive configuration with no current benefit; Modulith gives the same control through a test.
- **Creating modules only with the first code** — rejected: the B2 map is approved, and empty modules
  with explicit dependencies cost 11 `package-info.java` files and block wrong dependencies from day one.
- **ArchUnit without Modulith** — rejected: Modulith is already in the stack (ADR-0001) and also gives events,
  module tests and documentation.

## Consequences

- Positive: a violation of the module map ends in a red build with the exact dependency path.
- The `support`, `reporting` and `businessprofile` modules exist although their code will be written after the
  Pilot; they are only package declarations.
- The choice of data access layer (Spring Data JDBC vs JPA) is not part of this ADR. For now the backend has only
  `spring-boot-starter-jdbc` and Flyway; the decision will be made in B-06 together with the first tables.
- The plan mentioned "ADR-002: module convention"; number 0002 was taken by the tool calling boundary, hence 0003.
  The tenancy model (ADR-003 in the plan) will get the next free number in I3.

## Verification

`backend/`, `./gradlew check`: `ModularityTest` passes (2/2). Checked by mutation: a temporary
class in `identity` referencing `commerce` gives
`Module 'identity' depends on module 'commerce' … Allowed targets: none.` and a red build.
