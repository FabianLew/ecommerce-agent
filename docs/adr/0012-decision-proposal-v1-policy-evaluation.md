# ADR-0012: DecisionProposal v1, ocena polityki i InventoryGuardianWorkflow w trybie shadow

- Status: Proponowany
- Data: 2026-11-14
- Powiązania: dokument 02 §9, dokument 03 §4–8 i §12 (IG-10), dokument 05 §E2, plan (decyzja C2, wiersze 2, 3, 7 i
  11 tabeli przeglądu, decyzja I8), ADR-0002, ADR-0011, backlog B-21, B-22

## Kontekst

Propozycja decyzji to kontrakt między proponującym (dziś deterministyczny baseline, od F2 model) a backendem.
Odwołują się do niej zgody, ocena polityki, upoważnienia i UI, więc jej kształt jest trudny do zmiany. Decyzja C2
wybrała jeden kontrakt v1 z semantyką 1.2 (pięć dyspozycji) zamiast schematów 1.0, 1.1 i migracji. Decyzja I8
mówi, że pilot działa w L1, a L2 jest wyłączone. B-22 kończy się propozycją i oceną polityki, bez mutacji.

## Decyzja

1. **Kontrakt v1.**
   - **Koperta (ustawia ją backend):**
     - `schemaVersion: "1"`, `proposalId`, `revision`;
     - `subject` w postaci `{type: OPPORTUNITY, opportunityId}`;
     - `proposer` w postaci `{kind: BASELINE|AGENT, name, version}`;
     - `agentRunId` (null dla baseline), `contextSnapshotId`, `createdAt`, `expiresAt`.
   - **Część od proponującego:** `recommendation` z polami:
     - `disposition` (`RECOMMEND_ACTION`, `NO_ACTION`, `MONITOR`, `INSUFFICIENT_DATA`, `ESCALATE`);
     - `action`;
     - `monitorPlan`;
     - `evidence` (lista `{evidenceId, version}`);
     - `missingRequirements`, `reasonCodes`, `summary`, `uncertainties`.
   - **Akcja:** pierwszy wariant to `PAUSE_AD` z polami `targetType: META_AD`, `targetId` (nasz identyfikator
     reklamy) i `parameters: {desiredStatus: PAUSED}`. Akcji budżetowych nie ma do czasu ich procesów.
   - **Java:** sealed `DecisionSubject` (na start `OpportunitySubject`, `SupportCaseSubject` w MVP) i sealed
     `ActionSpec` (`PauseAd`). Bez generycznych map parametrów.
   - Pola wiedzy i profilu sklepu (`knowledgeReleaseRef`, `storeProfileRef`) dojdą jako opcjonalne. Każda zmiana
     znaczenia pola to nowa wersja schematu.
2. **JSON Schema i walidator.** Schemat 2020-12 leży w `knowledge/schemas/decision-proposal-v1.schema.json`
   (zamknięte pola i enumeracje, `oneOf` dla dyspozycji). Trafia do classpath backendu i jest sprawdzany biblioteką
   `com.networknt:json-schema-validator` 3.x (Jackson 3). Walidator ma trzy warstwy:
   - **Schemat:** struktura i typy.
   - **Domena:**
     - inwarianty dyspozycji: `RECOMMEND_ACTION` wymaga akcji, pozostałe mają `action: null`;
     - `monitorPlan` tylko przy `MONITOR`, z terminami w limitach;
     - `missingRequirements` niepuste przy `INSUFFICIENT_DATA`;
     - akcja z allowlisty procesu;
     - kody przyczyn z zamkniętej listy procesu;
     - wynik przed `expiresAt`.
   - **Dowody:**
     - każdy dowód istnieje w tym sklepie, ma tę wersję i należy do snapshotu propozycji;
     - dowody dotyczą wariantu epizodu albo reklamy celu;
     - cel akcji jest reklamą z mapowaniem w snapshocie.

   Odrzucenie podaje kody błędów. Nieważna propozycja nie staje się propozycją, tylko zapisem odrzuconej próby
   (`proposal_rejection`) z hashem i kodami (IG-10).
3. **Zapis i stany propozycji (moduł `decisions`).**
   - `decision_proposal` zawiera `UNIQUE (opportunity_id, revision)`, snapshot, proposera, dyspozycję, kanoniczną
     treść (ADR-0011 pkt 2), `payload_hash`, status, `created_at` i `expires_at`.
   - Treść i hash są niezmienne (trigger), zmienia się tylko status: `PROPOSED → APPROVED | REJECTED | EXPIRED |
     SUPERSEDED`.
   - Nowa analiza tego samego epizodu to nowa rewizja, a poprzednia aktywna przechodzi w `SUPERSEDED`.
   - Maszyna wykonania (`ActionExecution`) dojdzie z executorem w F3.
4. **BaselineProposer (moduł `intelligence`).** Deterministyczny proposer za tym samym kontraktem jest punktem
   odniesienia ewaluacji i fallbackiem przy awarii modelu. Reguły w kolejności:
   - nieaktualny albo nieznany stan, brak forecastu: `INSUFFICIENT_DATA`;
   - pokrycie nie niższe niż próg: `NO_ACTION`;
   - brak mapowanych reklam: `NO_ACTION` (`NO_MAPPED_ADS`);
   - tylko mapowania wieloproduktowe albo niezweryfikowane: `ESCALATE`;
   - zweryfikowane mapowanie jednego wariantu z aktywną reklamą i zgodnym fingerprintem: `RECOMMEND_ACTION` z jedną
     pauzą, a reklamy wieloproduktowe zostają wymienione w uzasadnieniu jako wyłączone.

   `MONITOR` jest obsłużony w workflow, choć baseline go na start nie zwraca.
5. **PauseAdPolicy i PolicyEvaluation (moduł `automation`).** Jedna typowana klasa polityki na typ akcji. Kolejność
   kontroli według 02 §9:
   1. izolacja i cel w sklepie;
   2. zdrowie integracji (`STALE` daje `DEFER`);
   3. ważność i świeżość (stan ≤ 120 s, stan reklamy ≤ 60 s);
   4. ryzyko i jakość (seria obserwacji, pełne okno popytu);
   5. mapowanie i kreacja (jeden wariant, weryfikacja ≤ 24 h, zgodny fingerprint);
   6. stan reklamy (`ACTIVE`, inaczej `DENY` z `ALREADY_PAUSED` albo `CONFLICT`);
   7. autonomia: L1 daje `REQUIRE_APPROVAL`, L2 tylko przy fladze, domyślnie wyłączonej.

   Wyniki to `ALLOW`, `REQUIRE_APPROVAL`, `DENY`, `DEFER`. `policy_evaluation` zapisuje propozycję i rewizję, klucz
   i wersję polityki (`inventory-protection@1.0.0`), pełne parametry, tryb (`EVALUATION_ONLY` w pilocie),
   wynik, listę kontroli z faktami i czas. Parametry są w konfiguracji aplikacji z jawną wersją. Konfiguracja per
   sklep w bazie dojdzie z ekranem polityk. Pilot nie ma kodu wystawiającego `AuthorizedAction`.
6. **InventoryGuardianWorkflow (moduł `automation`).** Każdy krok to aktywność, a stan workflow przechowuje tylko
   identyfikatory:
   1. `buildContext` (`ANALYZING`, nowy snapshot);
   2. `propose` (baseline);
   3. `validateAndPersist`;
   4. `evaluatePolicy`.

   Przejścia statusu epizodu zależą od dyspozycji:
   - `RECOMMEND_ACTION` z `REQUIRE_APPROVAL` albo `DENY`, a także `ESCALATE`: `AWAITING_DECISION`;
   - `RECOMMEND_ACTION` z `DEFER`: `WAITING_DATA`;
   - `NO_ACTION`: `CLOSED` (`NO_ACTION`);
   - `MONITOR`: `MONITORING` z timerem `nextReviewAt` i limitem reanaliz;
   - `INSUFFICIENT_DATA`: `WAITING_DATA` z timerem i limitem prób (potem `AWAITING_DECISION`).

   Sygnał `riskUpdated` z istotną zmianą faktów uruchamia nową rewizję. `riskResolved` zamyka epizod (`RESOLVED`) i
   oznacza aktywną propozycję jako `SUPERSEDED`. Timer TTL ustawia `EXPIRED`. W trybie shadow workflow nie czeka
   na zgodę: kończy się, gdy epizod jest zamknięty, albo czeka na sygnały do limitu czasu epizodu.

## Rozważane alternatywy

- **Schemat 1.0 z dokumentu 03 i późniejsza migracja do 1.2.** Odrzucone decyzją C2.
- **Walidacja tylko przez rekordy Java i ścisłą deserializację.** Model w F2 dostanie schemat JSON, a UI i testy
  jakości potrzebują tego samego artefaktu. Jeden schemat w repozytorium sprawdzany w runtime unika rozjazdu.
- **Ogólny silnik reguł (OPA, Drools).** Odrzucone w planie (wiersz 11).
- **Zapis nieważnej propozycji jako `REJECTED`.** Myliłby odrzucenie przez człowieka z błędem walidacji. Odrzucone
  na rzecz osobnej tabeli prób.

## Konsekwencje

- Nowa biblioteka `json-schema-validator` 3.x. Schemat jest współdzielony przez backend, testy i przyszły prompt.
- Przykłady z dokumentu 03 (`ACT`, `WAIT_FOR_DATA`, `evidenceIds`) nie są zgodne z v1. Testy i UI używają nowych
  nazw.
- Zmiana logiki workflow epizodu wymaga `Workflow.getVersion` i testu replay.

## Weryfikacja

- `DecisionProposalSchemaTest`: przykłady z `knowledge/schemas/examples` przechodzą schemat i deserializację, a
  nieznane pole, zła dyspozycja i akcja przy `NO_ACTION` są odrzucane.
- `ProposalValidatorTest` (IG-10): fikcyjny dowód, dowód spoza snapshotu, obcy sklep, inny typ celu i cel bez
  mapowania dają odrzucenie przed oceną polityki.
- `PauseAdPolicyTest`: każda kontrola i wynik, L1 daje `REQUIRE_APPROVAL`.
- `InventoryGuardianWorkflowTest`: scenariusz z planu (zapas 18, 49 sztuk w 7 dni, `ad-9001` i katalog `ad-9002`)
  daje `RECOMMEND_ACTION` dla `ad-9001`, `REQUIRE_APPROVAL` i `AWAITING_DECISION` bez mutacji, a do tego test
  replay.
