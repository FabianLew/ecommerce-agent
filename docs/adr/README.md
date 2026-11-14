# Architecture Decision Records

Każda decyzja architektoniczna trudna do odwrócenia, albo zmieniająca ustalenia z `docs/architecture/`,
ma własny ADR. Numeracja jest ciągła i nie wraca do użytych numerów.

Statusy: `Proponowany` → `Zaakceptowany` (po akceptacji właściciela w PR) → opcjonalnie `Zastąpiony przez ADR-XXXX`.

| ADR | Tytuł | Status |
| --- | --- | --- |
| [0001](0001-stack-versions.md) | Wersje stacku backendu | Zaakceptowany |
| [0002](0002-spring-ai-tool-calling.md) | Pętla narzędzi należy do runtime, nie do Spring AI | Zaakceptowany |
| [0003](0003-module-convention.md) | Konwencja modułów backendu | Zaakceptowany |
| [0004](0004-tenancy-and-store-scope.md) | Model tenancy i zaufany zakres sklepu | Zaakceptowany z poprawkami |
| [0005](0005-data-access.md) | Dostęp do danych przez JdbcClient i jawny SQL | Zaakceptowany |
| [0006](0006-frontend-and-session.md) | Frontend Next.js z sesją po stronie serwera (BFF) i klientem z kontraktu OpenAPI | Zaakceptowany |
| [0007](0007-woocommerce-connection.md) | Połączenie WooCommerce: sekrety, uwierzytelnianie i ochrona przed SSRF | Zaakceptowany |
| [0008](0008-woocommerce-import-and-stock-semantics.md) | Import WooCommerce (14 dni) i semantyka stanów magazynowych | Zaakceptowany |
| [0009](0009-woocommerce-webhooks-inbox-outbox.md) | Webhooki WooCommerce: rejestracja, podpis, inbox, normalizacja i outbox | Zaakceptowany |
| [0010](0010-event-dispatch-polling-reconciliation.md) | Przekazywanie zdarzeń do Temporal, polling i rekonsyliacja WooCommerce, Integration Health | Zaakceptowany |
| [0011](0011-evidence-snapshot-risk-detection-opportunity.md) | Dowody, ContextSnapshot, detektor ryzyka i epizod Opportunity | Proponowany |
| [0012](0012-decision-proposal-v1-policy-evaluation.md) | DecisionProposal v1, ocena polityki i InventoryGuardianWorkflow w trybie shadow | Proponowany |

Szablon: [0000-template.md](0000-template.md).
