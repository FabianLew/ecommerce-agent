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

## Webhooki WooCommerce (B-14)

- Publiczny `POST /api/v1/webhooks/woocommerce/{endpointKey}` (bez OIDC, ADR-0009): losowy klucz w adresie
  wskazuje połączenie, podpis `X-WC-Webhook-Signature` (HMAC-SHA256 surowego body) uwierzytelnia sklep.
  Ping Woo (`webhook_id=N`) dostaje 200 bez zapisu, zły podpis 401, nieznany klucz 404, body > 1 MiB 413.
- Sekret: `POST /api/v1/stores/{storeId}/integration-connections/{id}/webhook-secret` `{secret}` (generuje go
  przeglądarka, backend go nie zwraca). Adres dostawy w `webhook.deliveryUrl` z `WEBHOOK_BASE_URL`.
- `webhook_inbox` przechowuje zminimalizowany payload (bez danych klientów), deduplikacja po SHA-256 body.
  Worker co 2 s normalizuje wpisy (`WebhookInboxProcessor`), w tej samej transakcji zapisuje `outbox_event`.
- Stan nie cofa się (IG-04): `InventoryRepository.recordIfNewer` porównuje `date_modified_gmt` z ostatnią
  obserwacją (`STALE`, `DUPLICATE`, `AMBIGUOUS` → prośba o weryfikację, `RECORDED`).
- Testy: `WooWebhookApiTest`, `WebhookNormalizationTest`.

## Dispatcher outbox → Temporal (B-15)

- `EventDispatch` (worker, co 1 s) przekazuje `outbox_event` przez `signalWithStart` do workflow o stałych
  ID (ADR-0010): `inventory-{storeId}-{variantId}` (`VariantInventoryWorkflow`) i `store-sync-{connectionId}`
  (`StoreSyncWorkflow`). Workflow odrzuca znane `eventId`, więc powtórka nie tworzy drugiego workflow.
- Prośba o weryfikację, a także zmiana zamówienia bez nowszego stanu w ciągu `order-delay`, daje jeden świeży
  odczyt wariantu z Woo (`WooSourceRefresh`, obserwacja `POLL`). Nieznany produkt jest doczytywany do katalogu.
- Opóźnienia: `operator.automation.inventory.verify-delay` (10 s), `order-delay` (60 s),
  `operator.automation.catalog.refresh-delay` (30 s). Test replay: `temporal/variant-inventory-v1.json`.

## Polling, rekonsyliacja i Integration Health (B-16)

- Worker co minutę tworzy brakujące Temporal Schedules dla połączeń z udanym importem (`SyncSchedules`):
  `woo-poll-{connectionId}` (produkty i wariacje zmienione od watermarku, co 5 min) i
  `woo-reconcile-{connectionId}` (zamówienia z porównaniem `X-WP-Total`, co godzinę). Oba uruchamiają
  `IncrementalSyncWorkflow` jako aktor `woo-sync`.
- Watermarki (`sync_watermark`) przesuwają się tylko po pełnym odczycie; pokrycie zamówień rośnie tylko przy
  zgodnym liczniku. Każdy przebieg zapisuje `sync_check` z liczbą zmian i zgubionych webhooków.
- `health` w odpowiedzi integracji liczony przy odczycie: `NOT_READY`, `STALE` (blokuje analizę),
  `DEGRADED`, `HEALTHY`, z kodami powodów (`IntegrationHealthService`).
- Ustawienia: `operator.automation.sync.poll-interval` (5 min), `reconcile-interval` (1 h),
  `schedules-enabled` (w testach `false`: serwer testowy Temporal nie ma Schedules).

## Metrics Engine v1 (B-17)

- `InventoryMetrics.coverage(scope, variantId, asOf)` liczy `UNITS_SOLD@v1`, `SALES_VELOCITY_7_FULL_LOCAL_DAYS@v1`
  i `DAYS_OF_COVER@v1` deterministycznie, bez LLM (dokument 03 §2, §4).
- Okno: 7 pełnych dni lokalnych sklepu przed dniem `asOf`, po `placed_at`; statusy kwalifikowane v1:
  `processing`, `completed`, `on-hold`. Ułamki: skala 4, HALF_UP (IG-01: 18 / (49 / 7) = 2.5714).
- Brak danych to `null` z przyczyną (`MetricReason`), nigdy zero: niepełne zamówienia w oknie, brak sprzedaży,
  stan nieśledzony lub wspólny dla wariantów, brak odczytu stanu. Stockout w oknie oznacza prognozę jako
  ograniczoną (IG-25).
- Testy złote: `InventoryMetricsGoldenTest` (IG-01, IG-02, IG-03, IG-25).

## Dowody i ContextSnapshot (B-18)

- `EvidenceStore` (moduł `metrics`) zapisuje niezmienne dowody z hashem kanonicznego rekordu (`CanonicalJson`,
  ADR-0011). Identyczna treść w sklepie to ten sam dowód; trigger bazy odrzuca zmianę i usunięcie.
- `InventoryEvidence` utrwala dowody forecastu (stan, sprzedaż, tempo, pokrycie) z lineage. Dowody powstają tylko
  przy budowie kontekstu decyzji, nie przy zapytaniach analityki.
- `ContextSnapshots` (moduł `decisions`) zapisuje snapshot raz, z listą dowodów tego samego sklepu; odczyt tylko
  przez `TrustedStoreScope`.

## Reklamy i mapowania SKU–reklama (B-20)

- `AdRepository` (moduł `commerce`): reklamy Meta (własny i efektywny status, fingerprint kreacji) i mapowania na
  warianty z wersją rosnącą przy każdej zmianie. Weryfikacja dotyczy kreacji, dla której ją wykonano
  (`AdMapping.currentFor`).
- Do czasu adaptera Meta (F2) lokalny worker co minutę wczytuje `infra/fixtures/meta-ads.json`
  (`operator.commerce.ad-fixture-file`): dedykowaną reklamę `ad-9001` dla `TSHIRT-BASIC-S` i katalogową `ad-9002`.

## Detektor ryzyka i Opportunity (B-19)

- `InventoryRiskDetector` (`inventory-risk/1`, moduł `decisions`) zapisuje obserwację przy każdej ocenie: ryzyko to
  pokrycie poniżej `threshold-days`; stan starszy niż `max-stock-age` albo niedostępna metryka daje `UNKNOWN`, które
  przerywa serię. Epizod otwiera się po `min-observations` aktualnych obserwacjach ryzyka odległych o `min-gap`
  (`operator.inventory-guardian.risk.*`).
- `Opportunities`: co najwyżej jeden aktywny epizod na wariant (indeks unikalny, IG-05), przejścia statusów przez
  maszynę stanów z kontrolą wersji.
- `VariantInventoryWorkflow` (wersja `risk-detection`) ocenia ryzyko po zmianie stanu i po świeżym odczycie, a gdy
  seria się buduje, po `min-gap` potwierdza stan kolejnym odczytem ze sklepu.

## Kontrakt DecisionProposal v1 (B-21)

- Schemat `knowledge/schemas/decision-proposal-v1.schema.json` (JSON Schema 2020-12) trafia do classpath jako
  `schemas/…` i jest jedynym źródłem kształtu kontraktu (ADR-0012); przykłady są w `knowledge/schemas/examples`.
- `DecisionProposals.submit` opakowuje rekomendację proposera w kopertę v1 i sprawdza ją `ProposalValidator`
  (schemat, reguły procesu z `ProposalRules`, dowody i cel w snapshocie). Ważna propozycja to kolejna rewizja
  epizodu (poprzednia przechodzi w `SUPERSEDED`); nieważna to tylko wpis w `proposal_rejection` z kodami (IG-10).
- `AdEvidence` (moduł `metrics`) zapisuje dowody stanu reklamy i mapowania, na których opiera się walidacja celu.

## Zasady

- Moduły to podpakiety `com.ecommerceagent`; dozwolone zależności są w `package-info.java` każdego modułu.
- Endpointy sklepu przyjmują `TrustedStoreScope` jako parametr kontrolera; zapytania filtrują po
  `organization_id`/`store_id` z tego obiektu (ADR-0004). Dostęp do danych: `JdbcClient` + SQL (ADR-0005).
- Migracje Flyway w `src/main/resources/db/migration`, czas zapisywany w UTC.
