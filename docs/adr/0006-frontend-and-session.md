# ADR-0006: Frontend Next.js z sesją po stronie serwera (BFF) i klientem z kontraktu OpenAPI

- Status: Proponowany
- Data: 2026-11-14
- Powiązania: plan (decyzja I5: Spring Security + OIDC, Keycloak), dokument 04 §2–3, §14–15, backlog B-09,
  B-10, ADR-0001 (zasada wersji), ADR-0004 (zakres sklepu)

## Kontekst

B-09 wymaga frontendu Next.js z TypeScript, logowania OIDC z sesją w ciasteczku, `AppShell`, `StoreSwitcher`
i klienta generowanego z OpenAPI. To pierwsza warstwa, która trzyma tokeny użytkownika, więc decyzje o tym, gdzie
leżą tokeny i jak frontend rozmawia z API, są trudne do odwrócenia. Dokument 04 wymaga, żeby backend sprawdzał
dostęp do sklepu przy każdym żądaniu, a cache i kontekst sklepu nie przeciekały po przełączeniu konta lub roli
(UX-13).

Ograniczenia: tylko wydania GA starsze niż ok. 2 tygodnie (ADR-0001), bez dodatkowej infrastruktury w MVP
(np. Redis), małe zależności.

## Decyzja

1. **Stack:** Next.js 16.4.3 (App Router), React 19.3.1, TypeScript 5.9.3 w trybie `strict`, Node 22 LTS,
   pnpm 10 (wersja w `packageManager`, instalowana przez corepack). TypeScript 6/7 odłożone: `openapi-typescript`
   wymaga jeszcze TS 5.
2. **Wzorzec BFF: tokeny nie trafiają do przeglądarki.** Serwer Next.js jest poufnym klientem OIDC
   `operator-web` (Authorization Code + PKCE + `state` + `nonce`, biblioteka `openid-client` 6.8.8). Przeglądarka
   dostaje tylko zaszyfrowane ciasteczko sesji. Wywołania API idą z Server Components i Route Handlers z
   nagłówkiem `Authorization: Bearer`, bez cache (`no-store`).
3. **Sesja w jednym zaszyfrowanym ciasteczku `op_session`:** JWE `dir` + `A256GCM` (`jose` 6.2.14), klucz
   wyprowadzony z `SESSION_SECRET` przez HKDF osobno dla sesji i dla transakcji logowania. Atrybuty: `HttpOnly`,
   `SameSite=Lax`, `Path=/`, `Secure` przy `https`, wygasa razem z refresh tokenem. Zawartość: `sub`, access
   token, refresh token i ich czasy wygaśnięcia. ID token jest sprawdzany przy logowaniu i nie jest zapisywany.
   Zmierzony rozmiar ciasteczka: ok. 2,7–3,3 KB przy limicie 4 KB; test e2e pilnuje progu 3,5 KB.
4. **Odświeżanie:** `proxy.ts` (dawniej middleware) odświeża access token, gdy zostało mniej niż 30 s, i
   przekazuje nową sesję renderowanej stronie. Nieudane odświeżenie kończy sesję i prowadzi do logowania z
   zachowaniem bezpiecznego `returnTo` (tylko ścieżki tego samego originu).
5. **Wylogowanie:** `POST /auth/logout`, tylko z naszego originu (ochrona CSRF). Serwer kończy sesję Keycloak
   kanałem zwrotnym (endpoint `end_session` z refresh tokenem i sekretem klienta) i czyści ciasteczko.
6. **Kontrakt API:** springdoc-openapi 3.1.1 generuje opis `/api/v1` w testach backendu. Plik
   `contracts/openapi/operator-api.json` jest w repozytorium, a `OpenApiContractTest` sprawdza, że odpowiada
   działającemu API. Frontend generuje z niego typy (`openapi-typescript` 7.13.0) i używa `openapi-fetch` 0.17.0.
   CI sprawdza, że wygenerowany plik jest aktualny. Endpoint `/v3/api-docs` jest domyślnie wyłączony.
7. **Routing i sklep:** wszystkie widoki mają prefiks `/stores/{storeId}` (04 §2). Sklep zawsze weryfikuje
   backend; dla cudzego lub nieistniejącego sklepu UI pokazuje bezpieczny stan „niedostępny” i nie przełącza
   sklepu samo. Zmiana sklepu zachowuje sekcję, a usuwa identyfikatory zasobów i parametry poprzedniego sklepu.
8. **Testy:** Vitest 4.1.12 dla logiki (sesja, `returnTo`, ścieżki sklepu), Playwright 1.64.1 dla e2e z
   prawdziwym Keycloak, Postgres i backendem. Oba w CI.

## Rozważane alternatywy

- **Auth.js (next-auth)** — odrzucone: linia 5 jest wciąż w fazie beta (zasada GA), a 4.x to starsza linia
  projektowana pod Pages Router. Własny kod logowania to ok. 200 linii na `openid-client`, który robi
  weryfikację protokołu (state, PKCE, nonce, podpis i claims ID tokenu).
- **Tokeny w przeglądarce (SPA + PKCE, klient publiczny)** — odrzucone: access token dostępny dla JavaScriptu
  zwiększa skutki XSS; dokument 04 i plan zakładają sesję w ciasteczku.
- **Sesja po stronie serwera w Redis/Postgres** — odłożone: wymaga dodatkowego magazynu albo endpointów sesji w
  backendzie. Wrócimy do tego, gdy tokeny przestaną mieścić się w ciasteczku albo będzie potrzebne zdalne
  unieważnianie sesji z poziomu aplikacji.
- **Ręcznie pisany klient API** — odrzucone: rozjazd typów z backendem wychodzi dopiero w runtime.

## Konsekwencje

- Komponenty klienckie nie wołają backendu bezpośrednio. Gdy pojawią się komendy z UI (B-24), dodamy trasę
  proxy BFF w Next.js z ochroną CSRF (Origin + `SameSite`), z `Idempotency-Key` i `If-Match` zgodnie z 04 §3.
- Zmiana API wymaga: `./gradlew test --tests '*OpenApiContractTest' -PupdateOpenApi`, potem `pnpm generate:api`.
- Lokalny realm wydaje access tokeny ważne 60 s, żeby odświeżanie było stale ćwiczone (także w e2e).
- Redirect URI klienta `operator-web` to wyłącznie `/auth/callback` (bez wildcardów).
- Paleta z 04 §2 zmierzona (README frontendu): tekst i akcent spełniają 4,5:1; obramowanie `#DFE7E2` (1,26:1)
  jest tylko dekoracyjne, kontrolki formularzy mają obramowanie `#74867E` (3,85:1).
- Ryzyko: Keycloak może wydawać większe tokeny (więcej claims), a ciasteczko przekroczy limit. Mitygacja: test
  rozmiaru w e2e; wtedy dzielenie ciasteczka albo sesja po stronie serwera.
- Ryzyko: refresh token w ciasteczku jest szyfrowany, ale wyciek `SESSION_SECRET` ujawnia sesje. Sekret
  pochodzi z magazynu sekretów środowiska; rotacja unieważnia wszystkie sesje.

## Weryfikacja

`frontend/`: `pnpm typecheck`, `pnpm test` (17 testów), `pnpm build`, `pnpm test:e2e` (6 scenariuszy przeciw
lokalnemu stackowi): logowanie i przełączanie sklepu z zachowaniem sekcji, bezpieczny stan dla cudzego sklepu,
`HttpOnly` i brak JWT w stronie, wylogowanie kończące sesję Keycloak, odrzucenie wylogowania z obcego originu,
odświeżenie tokenu. Mutacyjnie: wyłączenie odświeżania powoduje porażkę testu odświeżenia. Backend:
`OpenApiContractTest`.
