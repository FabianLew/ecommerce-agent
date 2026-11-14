# Frontend (Next.js)

Aplikacja webowa operatora: logowanie przez Keycloak, wybór sklepu, `AppShell` z ośmioma sekcjami z
dokumentu 04 §2. Decyzje: [ADR-0006](../docs/adr/0006-frontend-and-session.md).

## Uruchomienie lokalne

Wymaga Node 22 (`corepack enable` instaluje pnpm w wersji z `package.json`), środowiska z
`infra/docker-compose.yml` i backendu z profilami `api,local`.

```bash
docker compose -f ../infra/docker-compose.yml up -d --wait postgres keycloak temporal
(cd ../backend && ./gradlew bootRun --args='--spring.profiles.active=api,local')   # osobny terminal
cp .env.example .env.local
pnpm install
pnpm dev                         # http://localhost:3000, użytkownicy owner-a / owner-b (hasło = login)
```

`owner-a` jest właścicielem „Sklep A” i analitykiem w „Sklep C” (przełączanie sklepu, różne uprawnienia).
`owner-b` widzi tylko „Sklep B”.

## Jak działa sesja

- Przeglądarka dostaje tylko ciasteczko `op_session` (zaszyfrowane JWE, `HttpOnly`, `SameSite=Lax`). Tokeny
  zostają na serwerze Next.js; komponenty klienckie nie wołają backendu bezpośrednio.
- `src/proxy.ts` wymaga sesji na każdej stronie i odświeża access token przed wygaśnięciem.
- `/auth/login` → Keycloak (PKCE, state, nonce) → `/auth/callback`. `POST /auth/logout` kończy też sesję
  Keycloak.
- Dane z API pobierają Server Components przez `backend()` z `src/lib/api.ts` (bez cache).

## Klient API

Typy w `src/api/schema.d.ts` są generowane z `../contracts/openapi/operator-api.json`. Nie edytuj ich ręcznie.

```bash
(cd ../backend && ./gradlew test --tests '*OpenApiContractTest' -PupdateOpenApi)   # po zmianie API
pnpm generate:api
```

CI (`pnpm check:api`) nie przejdzie, jeśli wygenerowany plik nie odpowiada kontraktowi.

## Testy

```bash
pnpm typecheck && pnpm test      # logika sesji, returnTo, ścieżki sklepu
pnpm build && pnpm test:e2e      # Playwright przeciw lokalnemu stackowi (backend i Keycloak muszą działać)
```

W środowisku z gotowym Chromium ustaw `PW_CHROMIUM_PATH` zamiast `pnpm exec playwright install chromium`.

## Kontrast palety (04 §2, §14)

Zmierzone współczynniki kontrastu (WCAG 2.x):

| Para | Kontrast | Użycie |
| --- | --- | --- |
| tekst `#172B25` na `#FFFFFF` / `#F6F8F7` | 14,91 / 13,98 | treść |
| pomocniczy `#52665E` na `#FFFFFF` / `#F6F8F7` | 6,14 / 5,76 | etykiety, opisy |
| akcent `#16734B` na `#FFFFFF`, biały na akcencie | 5,85 | linki, przycisk główny |
| akcent `#16734B` na `#EAF5EE` | 5,24 | aktywna pozycja menu |
| obramowanie `#DFE7E2` na `#FFFFFF` | 1,26 | tylko dekoracyjne (karty) |
| obramowanie kontrolek `#74867E` na `#FFFFFF` | 3,85 | pola, przyciski drugorzędne (wymóg 3:1) |
