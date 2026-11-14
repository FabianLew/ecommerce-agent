# ecommerce-agent

Autonomous E-commerce Operator — operator działający w tle dla istniejących sklepów DTC
(WooCommerce + Meta Ads + e-mail obsługi klienta). Wykrywa problemy, przygotowuje decyzje
z dowodami, wykonuje dozwolone działania i weryfikuje ich skutek.

> Status: wczesna implementacja (iteracja I1). Kod aplikacji jeszcze nie istnieje;
> repozytorium zawiera dokumentację architektury, decyzje (ADR) i spike'i technologiczne.

## Struktura repozytorium

| Ścieżka | Zawartość | Od iteracji |
| --- | --- | --- |
| `docs/architecture/` | Dokumenty 01–05: produkt, architektura AI, Inventory Guardian E2E, UI/UX, Decision Intelligence | I1 |
| `docs/adr/` | Architecture Decision Records | I1 |
| `spikes/` | Jednorazowe eksperymenty weryfikujące decyzje techniczne (nie są kodem produkcyjnym) | I1 (B-02, B-03) |
| `backend/` | Modularny monolit Spring Boot (profile `api`, `worker`) | I2 |
| `frontend/` | Next.js (App Router, TypeScript) | I4 |
| `knowledge/` | Skills, playbooks, JSON Schema kontraktów, przypadki ewaluacyjne | I7 |
| `infra/` | Docker Compose dla środowiska lokalnego | I2 |

## Dokumentacja

Źródłem prawdy o produkcie i architekturze są dokumenty w `docs/architecture/`
(kolejność lektury: 01 → 02 → 03 → 04 → 05). Decyzje podjęte w trakcie implementacji,
które doprecyzowują lub zmieniają te dokumenty, są zapisywane jako ADR w `docs/adr/`.

## Spike'i iteracji I1

Spike'i B-02 i B-03 (zgodność wersji stacku, granica tool calling w Spring AI) są dostarczane w osobnym PR.
