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

## Zasady

- Moduły to podpakiety `com.ecommerceagent`; dozwolone zależności są w `package-info.java` każdego modułu.
- Migracje Flyway w `src/main/resources/db/migration`, czas zapisywany w UTC.
