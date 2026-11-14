# backend

Modularny monolit Spring Boot (wersje: [ADR-0001](../docs/adr/0001-stack-versions.md),
moduły: [ADR-0003](../docs/adr/0003-module-convention.md)). Jeden artefakt, dwa profile:

| Profil | Rola |
| --- | --- |
| `api` | REST API dla frontendu (port `PORT`, domyślnie 8080), `/actuator/health` |
| `worker` | Przetwarzanie w tle (workery Temporal od B-08), bez serwera HTTP |

## Wymagania

- JDK 21 (Gradle toolchain), Docker (Testcontainers w testach).

## Polecenia

```bash
./gradlew check                                   # testy jednostkowe, integracyjne (Testcontainers) i granice modułów
./gradlew bootRun --args='--spring.profiles.active=api'
```

Połączenie z bazą: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (domyślnie lokalny PostgreSQL z `infra/`).
OIDC: `OIDC_ISSUER_URI` (domyślnie realm `operator` z lokalnego Keycloak) i `OIDC_AUDIENCE` (`operator-api`).

Obserwowalność (B-07): logi JSON (ECS) na stdout z `requestId`, `correlationId`, `traceId`, `spanId`.
Każda odpowiedź ma nagłówki `X-Request-Id` i `X-Correlation-Id` (przyjmowane od klienta tylko w bezpiecznym
formacie). Eksport śladów OTLP: `OTEL_EXPORT_ENABLED=true`, adres `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`
(domyślnie lokalny Jaeger z `infra/`), próbkowanie `TRACING_SAMPLING_PROBABILITY` (domyślnie 1.0).

Profil `local` (łączony z `api` lub `worker`) dokłada demo-tenantów zgodnych z użytkownikami Keycloak:
organizacja „Demo”: `owner-a` jest właścicielem „Sklep A” i analitykiem w „Sklep C”, `owner-b` widzi tylko „Sklep B”.

```bash
./gradlew bootRun --args='--spring.profiles.active=api,local'
TOKEN=$(curl -s -d grant_type=password -d client_id=operator-dev-cli -d username=owner-a -d password=owner-a \
  http://localhost:8180/realms/operator/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/stores
```

## API (stan na B-07)

| Endpoint | Opis |
| --- | --- |
| `GET /api/v1/me` | Bieżący użytkownik (rejestrowany przy pierwszym żądaniu) |
| `GET /api/v1/stores` | Sklepy, do których użytkownik ma członkostwo, z rolami |
| `GET /api/v1/stores/{storeId}` | Szczegóły sklepu; brak dostępu i brak sklepu dają ten sam `404 NOT_FOUND` |

Błędy mają format z dokumentu 04 §3: `{code, message, fieldErrors?, retryable, requestId, currentVersion?}`,
np. `UNAUTHENTICATED` (401), `FORBIDDEN` (403), `NOT_FOUND` (404), `METHOD_NOT_ALLOWED` (405),
`INTERNAL_ERROR` (500, bez szczegółów wyjątku; szczegóły są w logu z tym samym `requestId`).

## Temporal (B-08)

- Profil `worker` uruchamia workery (kolejka `operator-main`), profil `api` używa tylko klienta.
  Adres: `TEMPORAL_ADDRESS` (domyślnie `localhost:7233`), namespace `TEMPORAL_NAMESPACE`.
- Workflow startujemy przez `StoreContextPropagator.callWith(context, ...)`; aktywności czytają zakres
  wyłącznie z `ActivityStoreScope.current()` (zweryfikowany w bazie przez interceptor, ADR-0004).
- Diagnostyka (tylko profil `local`): `POST /api/v1/stores/{storeId}/diagnostics/workflow-scope-check`
  uruchamia workflow na workerze i zwraca zakres widziany przez aktywność.
- Testy workflow działają na in-process `TestWorkflowEnvironment`; test replay używa zapisanej historii
  `src/test/resources/temporal/scope-check-v1.json`. Po zamierzonej (wersjonowanej) zmianie workflow:
  `./gradlew test -PrecordTemporalFixtures` i nowa wersja pliku.

## Model handlu (B-11)

- Moduł `commerce`, migracja `V3__commerce.sql`: `product`, `product_variant` (jednostka sprzedaży), append-only
  `inventory_observation`, `customer_order` + `order_line` (bez danych osobowych klientów),
  `order_sync_coverage` (okres, za który zamówienia są kompletne).
- Tabele potomne wskazują rodzica przez `(id, store_id)`, więc wiersz nie może wskazywać danych innego sklepu.
- Upserty są idempotentne i ignorują dane starsze niż zapisany `source_updated_at`.
- Dostępny stan: `InventoryReading.availableToSell()` odejmuje rezerwacje tylko przy semantyce
  `SEPARATE_RESERVATIONS` (IG-02, bez podwójnego odejmowania).

## Integracje (B-12)

- Połączenie WooCommerce: `GET /api/v1/stores/{storeId}/integrations`, `POST .../integration-connections`,
  `POST .../integration-connections/{id}/credentials`, `POST .../integrations/{id}/checks` (ADR-0007).
- Sekrety są szyfrowane AES-256-GCM kluczem z `INTEGRATION_SECRETS_KEY` (base64, 32 bajty; bez niego aplikacja
  nie startuje). Profil `local` ma klucz deweloperski i pozwala na `http://` oraz adresy prywatne (lokalny Woo).
- Test z prawdziwym lokalnym WooCommerce: `WOO_LIVE_URL=http://localhost:8081 ./gradlew test --tests '*WooCommerceLiveTest'`.

## Import WooCommerce (B-13)

- `POST /api/v1/stores/{storeId}/integrations/{id}/sync-jobs` `{"scope":"INITIAL_14_DAYS"}` → 202 i przebieg
  `sync_run`; status: `GET .../sync-jobs/{jobId}` albo `lastSync` w `GET .../integrations`.
- Workflow `WooImportWorkflow` (profil `worker`) importuje katalog ze stanami, potem zamówienia od północy
  czasu sklepu 14 dni temu. Okres trafia do `order_sync_coverage` tylko przy zgodności z `X-WP-Total` (ADR-0008).
- Lokalnie: API (`api,local`) i worker (`worker,local --server.port=8090`), potem przycisk „Importuj dane z
  ostatnich 14 dni” w Integracjach.

## Metrics Engine v1 (B-17)

- `InventoryMetrics.coverage(scope, variantId, asOf)` liczy `UNITS_SOLD@v1`, `SALES_VELOCITY_7_FULL_LOCAL_DAYS@v1`
  i `DAYS_OF_COVER@v1` deterministycznie, bez LLM (dokument 03 §2, §4).
- Okno: 7 pełnych dni lokalnych sklepu przed dniem `asOf`, po `placed_at`; statusy kwalifikowane v1:
  `processing`, `completed`, `on-hold`. Ułamki: skala 4, HALF_UP (IG-01: 18 / (49 / 7) = 2.5714).
- Brak danych to `null` z przyczyną (`MetricReason`), nigdy zero: niepełne zamówienia w oknie, brak sprzedaży,
  stan nieśledzony lub wspólny dla wariantów, brak odczytu stanu. Stockout w oknie oznacza prognozę jako
  ograniczoną (IG-25).
- Testy złote: `InventoryMetricsGoldenTest` (IG-01, IG-02, IG-03, IG-25).

## Zasady

- Moduły to podpakiety `com.ecommerceagent`; dozwolone zależności są w `package-info.java` każdego modułu.
- Endpointy sklepu przyjmują `TrustedStoreScope` jako parametr kontrolera; zapytania filtrują po
  `organization_id`/`store_id` z tego obiektu (ADR-0004). Dostęp do danych: `JdbcClient` + SQL (ADR-0005).
- Migracje Flyway w `src/main/resources/db/migration`, czas zapisywany w UTC.
