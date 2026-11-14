# ADR-0004: Model tenancy i zaufany zakres sklepu

- Status: Proponowany
- Data: 2026-11-14
- Powiązania: plan (decyzja I6, B2 „ryzyka tenancy”), dokument 02 §3 (`RunContext`), dokument 04 §3, backlog B-06, B-08

## Kontekst

Jedna organizacja (tenant) ma wiele sklepów, a użytkownik może mieć dostęp do wielu sklepów z różnymi
rolami (I6). Zakres sklepu musi być taki sam w HTTP, w aktywnościach Temporal i w Agent Runtime, a model LLM
ani payload nie mogą go ustawić. Wyciek danych między sklepami jest najpoważniejszym ryzykiem produktu.

## Decyzja

1. **Model:** `organization` → `store`; `app_user` identyfikowany przez parę (`issuer`, `subject`) z tokenu
   OIDC; `store_membership` (organizacja, sklep, użytkownik, rola) — jeden wiersz na rolę.
2. **Każda tabela biznesowa ma `organization_id` i `store_id`.** Dla członkostwa spójność wymusza złożony
   klucz obcy (`store_id`, `organization_id`) → `store`.
3. **`TrustedStoreScope(organizationId, storeId, userId, roles)`** to jedyny typ zakresu przekazywany do
   modułów. Buduje go wyłącznie backend:
   - HTTP: z uwierzytelnionego użytkownika i `{storeId}` ze ścieżki, po sprawdzeniu członkostwa w bazie;
   - Temporal/aktywności (B-08) i Agent Runtime: z zapisanego rekordu, nigdy z argumentów modelu.
   Zapytania repozytoriów biorą `organizationId` i `storeId` z tego obiektu.
4. **Brak dostępu i brak zasobu dają tę samą odpowiedź `404 NOT_FOUND`** (dokument 04 §3), żeby nie
   ujawniać istnienia cudzych sklepów. Próba dostępu do cudzego lub nieistniejącego sklepu trafia do audytu
   (`STORE_ACCESS_DENIED`).
5. **Role:** robocze role z dokumentu 04 §11 [P]: `OWNER`, `DECISION_OPERATOR`, `SUPPORT_OPERATOR`,
   `ANALYST`. Macierz uprawnień ustalamy w O-13; do tego czasu B-06 sprawdza tylko członkostwo.
   Role żyją w naszej bazie, nie w Keycloak (Keycloak tylko uwierzytelnia).
6. **Audyt:** tabela `audit_event` tylko do dopisywania (trigger blokuje `UPDATE` i `DELETE`), w module
   `identity`, żeby każdy moduł mógł z niej korzystać zgodnie z kierunkiem zależności.
7. **Druga linia obrony (RLS PostgreSQL)** zostaje włączona przed pilotem (F4, E-45), zgodnie z planem.

## Rozważane alternatywy

- **Role i sklepy w claims tokenu Keycloak** — odrzucone: zmiana uprawnień wymagałaby nowego tokenu,
  a model danych sklepu i tak jest w naszej bazie; Keycloak zostaje wymienialnym dostawcą tożsamości.
- **`403` dla cudzego sklepu** — odrzucone: ujawnia istnienie zasobu (04 §3).
- **RLS od razu** — odłożone: wymaga `SET LOCAL` w każdej transakcji i komplikuje migracje i zapytania
  serwisowe; plan zakłada scope w repozytoriach + testy izolacji teraz, RLS w F4.

## Konsekwencje

- Każdy nowy endpoint sklepu przyjmuje `TrustedStoreScope` jako parametr; brak scope = brak dostępu.
- Pierwsze żądanie użytkownika rejestruje go w `app_user` (`USER_REGISTERED` w audycie). Zaproszenia i
  zarządzanie członkostwem to osobne zadanie (Settings → Zespół).
- Testy izolacji (`StoreIsolationTest`) są częścią testów bezpieczeństwa i blokują merge.

## Weryfikacja

`backend/`, `./gradlew check`, `StoreIsolationTest` (Testcontainers + tokeny JWT w MockMvc) — patrz PR B-06.
