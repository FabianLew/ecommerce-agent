# Architecture Decision Records

Każda decyzja architektoniczna trudna do odwrócenia, albo zmieniająca ustalenia z `docs/architecture/`,
ma własny ADR. Numeracja jest ciągła i nie wraca do użytych numerów.

Statusy: `Proponowany` → `Zaakceptowany` (po akceptacji właściciela w PR) → opcjonalnie `Zastąpiony przez ADR-XXXX`.

| ADR | Tytuł | Status |
| --- | --- | --- |
| [0001](0001-stack-versions.md) | Wersje stacku backendu | Zaakceptowany |
| [0002](0002-spring-ai-tool-calling.md) | Pętla narzędzi należy do runtime, nie do Spring AI | Zaakceptowany |
| [0003](0003-module-convention.md) | Konwencja modułów backendu | Zaakceptowany |
| [0004](0004-tenancy-and-store-scope.md) | Model tenancy i zaufany zakres sklepu | Proponowany |
| [0005](0005-data-access.md) | Dostęp do danych przez JdbcClient i jawny SQL | Proponowany |

Szablon: [0000-template.md](0000-template.md).
