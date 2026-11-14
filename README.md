# ecommerce-agent

Autonomous E-commerce Operator — operator działający w tle dla istniejących sklepów DTC
(WooCommerce + Meta Ads + e-mail obsługi klienta). Wykrywa problemy, przygotowuje decyzje
z dowodami, wykonuje dozwolone działania i weryfikuje ich skutek.

> Status: wczesna implementacja (iteracja I2): szkielet backendu, lokalne środowisko i CI.
> Logika domenowa jeszcze nie istnieje.

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
| `.github/workflows/` | CI: backend, testy bezpieczeństwa runtime, smoke test środowiska | I2 |

## Szybki start

```bash
docker compose -f infra/docker-compose.yml up -d --wait   # środowisko lokalne, szczegóły: infra/README.md
cd backend && ./gradlew check                            # wymaga JDK 21 i Dockera
```

## Dokumentacja

Źródłem prawdy o produkcie i architekturze są dokumenty w `docs/architecture/`
(kolejność lektury: 01 → 02 → 03 → 04 → 05). Decyzje podjęte w trakcie implementacji,
które doprecyzowują lub zmieniają te dokumenty, są zapisywane jako ADR w `docs/adr/`.

## Spike'i iteracji I1

- [`spikes/stack-compat`](spikes/stack-compat/README.md) — B-02 (zgodność wersji stacku, ADR-0001)
  i B-03 (granica tool calling w Spring AI, ADR-0002).
