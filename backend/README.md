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

Profil `local` (łączony z `api` lub `worker`) dokłada demo-tenantów zgodnych z użytkownikami Keycloak:
organizacja „Demo”, „Sklep A” dla `owner-a` i „Sklep B” dla `owner-b`.

```bash
./gradlew bootRun --args='--spring.profiles.active=api,local'
TOKEN=$(curl -s -d grant_type=password -d client_id=operator-dev-cli -d username=owner-a -d password=owner-a \
  http://localhost:8180/realms/operator/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/stores
```

## API (stan na B-06)

| Endpoint | Opis |
| --- | --- |
| `GET /api/v1/me` | Bieżący użytkownik (rejestrowany przy pierwszym żądaniu) |
| `GET /api/v1/stores` | Sklepy, do których użytkownik ma członkostwo, z rolami |
| `GET /api/v1/stores/{storeId}` | Szczegóły sklepu; brak dostępu i brak sklepu dają ten sam `404 NOT_FOUND` |

## Zasady

- Moduły to podpakiety `com.ecommerceagent`; dozwolone zależności są w `package-info.java` każdego modułu.
- Endpointy sklepu przyjmują `TrustedStoreScope` jako parametr kontrolera; zapytania filtrują po
  `organization_id`/`store_id` z tego obiektu (ADR-0004). Dostęp do danych: `JdbcClient` + SQL (ADR-0005).
- Migracje Flyway w `src/main/resources/db/migration`, czas zapisywany w UTC.
