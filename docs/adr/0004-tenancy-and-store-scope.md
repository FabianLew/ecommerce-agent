# ADR-0004: Model tenancy i zaufany zakres sklepu

- Status: Zaakceptowany z poprawkami (review właściciela, 2026-11-14; poprawki w punktach 2, 3, 5, 6, 8 i 9)
- Data: 2026-11-14
- Powiązania: plan (decyzja I6, B2 „ryzyka tenancy”), dokument 02 §3 (`RunContext`), dokument 04 §3, backlog B-06, B-08

## Kontekst

Jedna organizacja (tenant) ma wiele sklepów, a użytkownik może mieć dostęp do wielu sklepów z różnymi
rolami (I6). Zakres sklepu musi być taki sam w HTTP, w aktywnościach Temporal i w Agent Runtime, a model LLM
ani payload nie mogą go ustawić. Wyciek danych między sklepami jest najpoważniejszym ryzykiem produktu.

## Decyzja

1. **Model:** `organization` → `store`; `app_user` identyfikowany przez parę (`issuer`, `subject`) z tokenu
   OIDC; `store_membership` (organizacja, sklep, użytkownik, rola) — jeden wiersz na rolę.
2. **Każda tabela biznesowa zależna od sklepu ma `organization_id` i `store_id` oraz złożony klucz obcy
   (`store_id`, `organization_id`) → `store (id, organization_id)`.** Dotyczy to wszystkich takich tabel,
   nie tylko `store_membership`: baza nie przyjmie wiersza, który łączy sklep z inną organizacją. Regułę
   sprawdza test schematu (`IdentitySchemaTest`), który obejmuje automatycznie każdą nową tabelę z kolumną
   `store_id`. Jedyny udokumentowany wyjątek to `audit_event` (zdarzenia bez ustalonego zakresu, np. odmowa
   dostępu do nieistniejącego sklepu).
3. **`TrustedStoreScope` (organizacja, sklep, aktor, role, uprawnienia)** to jedyny typ zakresu przekazywany
   do modułów. Aktor to typ zamknięty:
   ```java
   sealed interface Actor {
       record UserActor(UUID userId) implements Actor {}
       record SystemActor(String service) implements Actor {}
   }
   ```
   `UserActor` to operacja w imieniu zalogowanego użytkownika, `SystemActor` to operacja usługi backendu
   bez użytkownika (np. agent monitorujący sklep w nocy). **Sam typ nie jest zabezpieczeniem.** Konstruktor
   `TrustedStoreScope` nie jest publiczny; zakres buduje wyłącznie zaufana fabryka `StoreAccess`, która
   sprawdza dane w bazie:
   - `scopeFor(userId, storeId)`: aktualne członkostwo użytkownika w sklepie;
   - `systemScope(service, organizationId, storeId)`: istnienie sklepu w tej organizacji, gdy proces
     systemowy zaczyna się od zapisanego rekordu (np. harmonogram monitorowania sklepu);
   - HTTP: użytkownik z tokenu i `{storeId}` ze ścieżki;
   - Temporal (B-08): `StoreContextPropagator` przenosi w nagłówkach workflow identyfikatory
     (`organizationId`, `storeId`, aktora: `userId` **albo** `service`) i `correlationId`. Nagłówek to tylko
     **deklaracja**: `StoreScopeActivityInterceptor` przed każdą aktywnością buduje zakres na nowo w
     `StoreAccess` (`scopeFor` dla użytkownika, `systemScope` dla usługi) i dopiero wtedy udostępnia
     `ActivityStoreScope.current()`. Brak, niejednoznaczność (oba aktory) lub niezgodność deklaracji kończy
     aktywność nieretryowalnym błędem `STORE_SCOPE_MISSING` / `STORE_SCOPE_REJECTED` (z wpisem w audycie).
     Workflow użytkownika, któremu odebrano dostęp, nie wykona więc kolejnej aktywności;
   - Agent Runtime: aktor i sklep z zaufanego kontekstu przebiegu, nigdy z argumentów modelu ani payloadu.
   Zapytania repozytoriów biorą `organizationId` i `storeId` z tego obiektu (ADR-0005).
4. **Brak dostępu i brak zasobu dają tę samą odpowiedź `404 NOT_FOUND`** (dokument 04 §3), żeby nie
   ujawniać istnienia cudzych sklepów. Próba dostępu do cudzego lub nieistniejącego sklepu trafia do audytu
   (`STORE_ACCESS_DENIED`). Członek sklepu bez wymaganego uprawnienia dostaje `403 FORBIDDEN`
   (istnienie sklepu nie jest dla niego tajemnicą) i zdarzenie `STORE_PERMISSION_DENIED`.
5. **Role i minimalne uprawnienia od B-06.** Robocze role z dokumentu 04 §11 [P]: `OWNER`,
   `DECISION_OPERATOR`, `SUPPORT_OPERATOR`, `ANALYST`. Kod sprawdza uprawnienia, nie nazwy ról.
   Od razu egzekwujemy dwa uprawnienia:

   | Uprawnienie | Znaczenie | Role |
   | --- | --- | --- |
   | `STORE_READ` | odczyt danych sklepu, decyzji i raportów | wszystkie role |
   | `STORE_MANAGE` | ustawienia sklepu, integracje, członkostwa | `OWNER` |

   Endpoint sklepu wymaga domyślnie `STORE_READ`; endpoint zmieniający sklep deklaruje `STORE_MANAGE`.
   Zakres systemowy ma tylko `STORE_READ`: działania systemu idą ścieżką polityki i executora
   (`AuthorizedAction`), a nie szerszym zakresem. Pełną macierz (np. zatwierdzanie decyzji, obsługa
   klienta) ustalamy w O-13 i rozszerzamy tę tabelę. Role żyją w naszej bazie, nie w Keycloak
   (Keycloak tylko uwierzytelnia).
6. **Zmiana uprawnień działa od razu, także dla wcześniejszych zatwierdzeń.** Zakres dowodzi tylko, że
   sprawdzenie przeszło w chwili jego zbudowania. Bezpośrednio przed wrażliwą akcją (wykonanie
   `AuthorizedAction`, zmiana ustawień, wysyłka) wykonawca woła `StoreAccess.reauthorize(scope, uprawnienie)`,
   które ponownie czyta członkostwo i role z bazy. Zatwierdzenie sprzed kilku godzin nie pozwala działać
   użytkownikowi, któremu odebrano dostęp lub rolę; akcja kończy się odmową i wpisem w audycie. Obowiązuje
   to niezależnie od 24-godzinnej ważności zatwierdzenia i rewalidacji stanu sklepu (I1).
7. **Audyt:** tabela `audit_event` tylko do dopisywania (trigger blokuje `UPDATE` i `DELETE`), w module
   `identity`, żeby każdy moduł mógł z niej korzystać zgodnie z kierunkiem zależności. Aktor zdarzenia to
   `actor_user_id` albo `actor_service` (nigdy oba).
8. **Retencja i minimalizacja danych osobowych w audycie (wymaganie).**
   - Audyt przechowuje identyfikatory (użytkownik, sklep, zasób), a nie dane osobowe: bez e-maili, treści
     wiadomości klientów i danych zamówień w `details`.
   - Przed pilotem (F4) wprowadzamy politykę retencji z okresem przechowywania (osobny ADR) i jej
     wykonanie przez zadanie systemowe, które jako jedyne może usuwać przeterminowane wpisy.
   - Trigger chroni przed błędem aplikacji, **nie przed uprzywilejowanym administratorem bazy** (może
     wyłączyć trigger lub zmienić dane). Docelowo aplikacja łączy się rolą bez praw `UPDATE`/`DELETE` do
     `audit_event`, a właścicielem tabeli jest osobna rola migracji; ewentualna niezmienność wobec
     administratora (eksport do zewnętrznego magazynu) to decyzja F4.
9. **Druga linia obrony (RLS PostgreSQL)** zostaje włączona przed pilotem (F4, E-45), zgodnie z planem.

## Rozważane alternatywy

- **Role i sklepy w claims tokenu Keycloak** — odrzucone: zmiana uprawnień wymagałaby nowego tokenu,
  a model danych sklepu i tak jest w naszej bazie; Keycloak zostaje wymienialnym dostawcą tożsamości.
- **`403` dla cudzego sklepu** — odrzucone: ujawnia istnienie zasobu (04 §3).
- **RLS od razu** — odłożone: wymaga `SET LOCAL` w każdej transakcji i komplikuje migracje i zapytania
  serwisowe; plan zakłada scope w repozytoriach + testy izolacji teraz, RLS w F4.

## Konsekwencje

- Każdy nowy endpoint sklepu przyjmuje `TrustedStoreScope` jako parametr; brak scope = brak dostępu.
  Endpoint zmieniający sklep oznacza parametr `@RequiresPermission(STORE_MANAGE)`.
- Każda nowa tabela sklepu dostaje złożony klucz obcy w tej samej migracji; inaczej test schematu nie przejdzie.
- Wykonawcy wrażliwych akcji (B-18+) muszą wołać `reauthorize` tuż przed wykonaniem; testy wykonawcy
  sprawdzają odmowę po odebraniu członkostwa.
- Pierwsze żądanie użytkownika rejestruje go w `app_user` (`USER_REGISTERED` w audycie). Zaproszenia i
  zarządzanie członkostwem to osobne zadanie (Settings → Zespół).
- Testy izolacji (`StoreIsolationTest`) są częścią testów bezpieczeństwa i blokują merge.

## Weryfikacja

`backend/`, `./gradlew check` (Testcontainers PostgreSQL), PR B-06:

- `StoreIsolationTest` (tokeny JWT w MockMvc): izolacja sklepów, nieodróżnialne 404, `403` dla członka bez
  `STORE_MANAGE`, audyt obu odmów, odebranie członkostwa działa od następnego żądania.
- `StoreAccessTest`: aktor i uprawnienia w zakresie, zakres systemowy tylko dla sklepu w danej organizacji,
  `reauthorize` odmawia po odebraniu członkostwa i po zmianie roli.
- `IdentitySchemaTest`: append-only audytu, aktor albo użytkownik, albo usługa, strażnik złożonych kluczy
  obcych (z testem, że wykrywa tabelę bez klucza).
