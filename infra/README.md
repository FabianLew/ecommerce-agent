# infra: lokalne środowisko (B-05)

Wszystkie hasła i klucze w tym katalogu są **wyłącznie lokalne**. Nie używaj ich w żadnym innym środowisku.

## Start

```bash
docker compose -f infra/docker-compose.yml up -d --wait
```

Pierwszy start trwa ok. 1–2 min. Jednorazowy kontener `woo-seed` instaluje WordPress i WooCommerce i tworzy
sklep testowy, po czym kończy się kodem 0. Kolejne starty pomijają seed (`operator_seed_version`).
Pełny reset: `docker compose -f infra/docker-compose.yml down -v`.

## Usługi

| Usługa | Adres | Dostęp |
| --- | --- | --- |
| PostgreSQL 17 | `localhost:5432` | baza `operator`, użytkownik/hasło `operator`; Keycloak ma osobną bazę `keycloak` |
| Temporal (dev server) | `localhost:7233`, UI http://localhost:8233 | brak auth |
| Keycloak 26 | http://localhost:8180 | admin: `admin`/`admin`; realm `operator` |
| Mailpit | SMTP `localhost:1025`, UI http://localhost:8025 | brak auth |
| Jaeger 2 | OTLP HTTP `localhost:4318`, UI http://localhost:16686 | brak auth; backend z `OTEL_EXPORT_ENABLED=true` |
| WooCommerce 11.2 (WordPress 7.1) | http://localhost:8081 | wp-admin: `admin`/`admin` |

Backend (`backend/`) łączy się domyślnie z tą bazą: `./gradlew bootRun --args='--spring.profiles.active=api'`.

## Keycloak: realm `operator`

Importowany z [`keycloak/operator-realm.json`](keycloak/operator-realm.json) przy pierwszym starcie.

- Klient `operator-web`: poufny (sesja po stronie serwera Next.js, B-09), Authorization Code + PKCE,
  redirect tylko `http://localhost:3000/auth/callback`, sekret `local-operator-web-secret`.
- Klient `operator-dev-cli`: publiczny, tylko password grant, **wyłącznie lokalnie** — do wywołań API z curl.
- Oba klienty dodają do tokenu audience `operator-api`; backend odrzuca tokeny bez niego.
- Access token jest ważny 60 s, żeby odświeżanie sesji frontendu było stale ćwiczone (ADR-0006).
- Użytkownicy `owner-a` i `owner-b` (hasło = login) mają stałe ID (`1111…`, `2222…`), na które wskazuje
  profil `local` backendu. Role per sklep nie są w Keycloak, tylko w naszej bazie (ADR-0004).

Zmiany w realmie: edytuj JSON i zrób `down -v` (import działa tylko dla nowego realmu).

## WooCommerce: dane testowe

| Produkt | SKU | Stan |
| --- | --- | --- |
| Koszulka Basic (wariantowy, atrybut Rozmiar) | `TSHIRT-BASIC-S` / `-M` / `-L` | 18 / 3 / 0 |
| Kubek Logo (prosty) | `MUG-LOGO` | 49 |

Waluta PLN, kraj PL, strefa czasowa UTC, zarządzanie stanem włączone.

Klucz REST API (tylko odczyt): `ck_local_operator_0000000000000000000000` /
`cs_local_operator_0000000000000000000000`.

> Lokalny sklep działa po HTTP. WooCommerce przyjmuje wtedy wyłącznie podpis **OAuth 1.0a**
> (Basic Auth i klucze w query string działają tylko po HTTPS). Adapter WooCommerce (B-12) musi to
> uwzględnić albo lokalny sklep dostanie TLS; decyzja w B-12.

Instalacja offline: ustaw `WOOCOMMERCE_SOURCE` w usłudze `woo-seed` na ścieżkę zamontowanego pliku
`woocommerce.zip` (domyślnie pobierany jest release z GitHub).
