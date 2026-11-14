# Instructions for Claude Code in this repository

## Context

- Product and architecture: `docs/architecture/01–05`. They are the source of truth; do not treat the [P]
  proposals in those documents as approved where an ADR says otherwise.
- Implementation decisions: `docs/adr/`. Every decision that is hard to reverse needs an ADR before the code.

## Working rules (agreed with the project owner)

- Talk to the owner in Polish. Every file in the repository is in English: code, class names, code comments and
  all documentation, including every `.md` file.
- Work in small iterations. Each iteration ends with a PR and a report (scope, test results, decisions, proposed
  next iteration) and waits for approval. Do not move on to the next iteration on your own.
- One PR = one backlog item or a small, coherent set. The PR description covers: what and why, how it was tested,
  risks.
- Do not add generic frameworks "just in case". An abstraction appears when a concrete process needs it.

## Architecture rules that do not change

- The LLM only proposes (`DecisionProposal`). The backend validates it, evaluates the policy, issues an
  `AuthorizedAction` and executes it through an executor with a ledger. The model sees READ tools only.
- Spring AI must not execute tools on its own: the tool loop belongs to our runtime (ADR-0002). The runtime calls
  `ChatModel` directly (not `ChatClient`), and the configuration always has
  `spring.ai.chat.client.tool-calling.enabled=false`.
- The tenant/store comes from a trusted `TrustedStoreScope`, never from model arguments or a payload. Only
  `StoreAccess` builds a scope (a user or a system actor); repository methods for store data take a
  `TrustedStoreScope` and filter by `organization_id` and `store_id`; store tables have a composite FK to
  `store (id, organization_id)`; a sensitive action calls `StoreAccess.reauthorize` right before it executes
  (ADR-0004, ADR-0005).
- Runtime safety tests must pass 100% and block the merge. LLM recommendation quality is evaluated separately
  (expert scenarios, comparison with the deterministic baseline) as a report, without requiring agreement.
- Money as `BigDecimal` or integers in minor units together with the currency; time in UTC.

## Environment note

`ANTHROPIC_BASE_URL` may be set in the developer tools' environment. Model integration tests must set the base URL
explicitly so they never reach an unexpected endpoint.
