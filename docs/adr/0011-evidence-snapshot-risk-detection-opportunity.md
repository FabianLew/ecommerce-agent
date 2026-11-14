# ADR-0011: Dowody, ContextSnapshot, detektor ryzyka i epizod Opportunity

- Status: Proponowany
- Data: 2026-11-14
- Powiązania: dokument 03 §3–4 i §12 (IG-04, IG-05), dokument 05 §C4 i §D2, dokument 02 §9, plan (wiersze 3 i 10
  tabeli przeglądu, mapa modułów B2), ADR-0004, ADR-0008, ADR-0010, backlog B-18, B-19, B-20

## Kontekst

I7 otwiera pierwszy epizod Inventory Guardian. Trudne do odwrócenia są tu cztery rzeczy:

- postać dowodu i jego hash, do których będą się odwoływać propozycje, zgody i audyt;
- niezmienność snapshotu kontekstu;
- reguła „dwóch aktualnych obserwacji ryzyka”, od której zależy, kiedy powstaje epizod;
- unikalność aktywnego epizodu, od której zależy deduplikacja workflow.

Plan (wiersz 10) zastrzega, że dowody utrwalamy tylko w kontekście decyzji, a nie przy każdym zapytaniu
analitycznym.

## Decyzja

1. **Evidence (moduł `metrics`).** Każdy dowód jest trwałym, niezmiennym rekordem w tabeli `evidence` z polami:
   - sklep;
   - `kind` (`OBSERVATION`, `DERIVED_METRIC`);
   - `evidence_type` (`STOCK_READING`, `UNITS_SOLD`, `SALES_VELOCITY`, `DAYS_OF_COVER`, `REPLENISHMENT`,
     `AD_STATE`, `AD_MAPPING`);
   - `resource_ref` (`PRODUCT_VARIANT:{id}`, `AD:{id}`, `AD_MAPPING:{id}`);
   - wersja definicji metryki;
   - kanoniczna treść (`content`);
   - `content_hash`;
   - `version` (zawsze 1, nowe fakty to nowy dowód);
   - czasy `observed_at`, `available_at`, `computed_at`;
   - lista dowodów wejściowych.

   Dowody powstają tylko w Context Builderze, nigdy przy zapytaniach analitycznych. Trigger bazy odrzuca `UPDATE` i
   `DELETE`. Identyczna treść w tym samym sklepie daje ten sam dowód (`UNIQUE (store_id, content_hash)`).
2. **Kanoniczny rekord i hash.** Kanoniczny rekord to JSON z kluczami w kolejności leksykograficznej, bez zbędnych
   białych znaków.
   - Liczby dziesiętne są tekstem w notacji zwykłej ze stałą skalą definicji. Liczby całkowite są liczbami. Nie
     używamy liczb zmiennoprzecinkowych.
   - Czas zapisujemy w UTC w formacie ISO-8601 z dokładnością do sekundy.
   - `content_hash = "sha256:" + hex(SHA-256(UTF-8))` liczymy z rekordu bez identyfikatora, czasu obliczenia i czasu zapisu, ale ze
     sklepem i typem.

   Tę samą kanonizację stosujemy do snapshotu i propozycji (ADR-0012).
3. **ContextSnapshot (moduł `decisions`).** `context_snapshot` zawiera:
   - sklep;
   - epizod;
   - `as_of`;
   - schemat treści (`inventory-context/1`);
   - kanoniczną treść;
   - hash.

   `context_snapshot_evidence` wiąże snapshot z dowodami. Zapis odbywa się raz, a trigger odrzuca zmiany. Odczyt
   jest możliwy tylko przez `TrustedStoreScope`, więc obcy sklep dostaje „brak”. Snapshot jest też manifestem
   kontekstu pilota. Referencje do wiedzy i profilu sklepu (05 §D2) dojdą jako pola dodatkowe w nowej wersji
   schematu treści.
4. **Detektor ryzyka (moduł `decisions`, wywoływany z `VariantInventoryWorkflow`).** Każda ocena zapisuje
   obserwację w `inventory_risk_observation` z polami:
   - wariant;
   - czas;
   - potwierdzenie stanu;
   - `available`;
   - `days_of_cover`;
   - wynik `AT_RISK`, `NOT_AT_RISK` albo `UNKNOWN` z przyczyną;
   - próg i wersja detektora (`inventory-risk/1`).

   Reguła działania:
   - Obserwacja jest aktualna, gdy stan potwierdził odczyt nie starszy niż `max-stock-age` (120 s). Potwierdzeniem
     jest odczyt źródła albo świeży zapis z webhooka lub pollingu.
   - `AT_RISK` oznacza `daysOfCover < threshold-days` (3).
   - `UNKNOWN` (brak forecastu, nieaktualny stan, wariant bez śledzenia albo ze wspólnym stanem) przerywa serię.
   - Epizod otwierają co najmniej dwie kolejne aktualne obserwacje `AT_RISK` odległe o co najmniej `min-gap`
     (5 min).

   Workflow wariantu ocenia ryzyko po każdej zmianie stanu. Po obserwacji `AT_RISK` bez epizodu ustawia timer na
   `min-gap` i wtedy ocenia ponownie po świeżym odczycie wariantu z Woo. Dzięki temu druga obserwacja nie zależy od
   tego, czy sklep coś wyśle. Parametry pochodzą z konfiguracji (`operator.inventory-guardian.risk.*`) i są
   zapisane w każdej obserwacji. Ustawienia per sklep dojdą z profilem sklepu. Osobny, szybszy próg dla
   potwierdzonego zera zostaje na później.
5. **Opportunity (moduł `decisions`).** `opportunity` zawiera sklep, kategorię `INVENTORY_RISK`, podmiot
   (`PRODUCT_VARIANT`, id), status, wersję, czasy i przyczynę zamknięcia.
   - Częściowy indeks unikalny `(store_id, category, subject_type, subject_id) WHERE status NOT IN ('RESOLVED',
     'CLOSED')` gwarantuje jeden aktywny epizod (IG-05).
   - Otwarcie to `INSERT … ON CONFLICT DO NOTHING`, a przy konflikcie odczyt istniejącego. Drugi detektor dostaje
     ten sam epizod.
   - Statusy to `OPEN`, `ANALYZING`, `AWAITING_DECISION`, `MONITORING`, `WAITING_DATA`, `ACTING`, `RESOLVED`,
     `CLOSED`. Tabela dozwolonych przejść jest w kodzie (`OpportunityStatus.canMoveTo`), a zmiana działa jako
     compare-and-set na wersji.
   - Propozycje i wykonania mają własne maszyny stanów (ADR-0012), więc status Opportunity opisuje tylko cykl
     problemu.
6. **Start epizodu.** Po otwarciu epizodu workflow wariantu woła aktywność, która startuje
   `InventoryGuardianWorkflow` z ID `inventory-guardian-{storeId}-{variantId}-{opportunityId}` i polityką
   `REJECT_DUPLICATE`. Przy aktywnym workflow wysyła mu sygnał aktualizacji ryzyka. Powtórka aktywności po awarii
   znajduje ten sam epizod, a start z tym samym ID nie tworzy drugiego procesu. Nie używamy outboxa, bo inicjator
   (workflow wariantu) jest już trwały. Zdarzenia biznesowe `inventory.risk.*.v1` dojdą, gdy pojawi się ich
   konsument (powiadomienia, read model).
7. **Reklamy i mapowania (moduł `commerce`, B-20).**
   - `ad` zawiera platformę `META`, zewnętrzne ID, nazwę, własny i efektywny status, fingerprint kreacji, czas
     odczytu i źródło (`FIXTURE`, później `META_API`).
   - `ad_mapping` zawiera reklamę, zakres (`SINGLE_VARIANT`, `MULTI_PRODUCT`), `version` zwiększaną przy każdej
     zmianie, status weryfikacji (`VERIFIED`, `UNVERIFIED`, `REVOKED`), czas weryfikacji i fingerprint kreacji z
     chwili weryfikacji.
   - `ad_mapping_variant` wiąże mapowanie z wariantami.
   - Do czasu adaptera Meta (F2) reklamy i mapowania wczytujemy z pliku fixture w profilu `local` i w testach.

## Rozważane alternatywy

- **Dowody liczone przy każdym zapytaniu analityki.** Odrzucone w planie (wiersz 10): wolumen bez potrzeby.
- **Hash z JSON zapisanego przez Jackson bez kanonizacji.** Kolejność pól i format liczb zależą od wersji
  biblioteki, a hash zgody musi być stabilny. Odrzucone.
- **Druga obserwacja tylko z kolejnego zdarzenia.** Gdy stan się nie zmienia, nie ma kolejnego zdarzenia, a
  polling nie zapisuje identycznego stanu. Ryzyko nie otworzyłoby epizodu. Odrzucone na rzecz timera i świeżego
  odczytu.
- **Start workflow epizodu przez outbox.** Byłby to drugi mechanizm obok trwałego workflow wariantu, bez zysku
  niezawodności. Odrzucone na pilota.
- **Jeden status Opportunity z 16 wartościami.** Odrzucone w planie (wiersz 3).

## Konsekwencje

- Wariant w ryzyku daje dodatkowy odczyt Woo co 5 minut do chwili otwarcia epizodu. Po otwarciu ocena działa tylko
  na zmiany stanu.
- Dowody i snapshoty rosną z liczbą analiz. Retencja przyjdzie z retencją audytu (F4) i nie może usuwać dowodów
  użytych w decyzji.
- Zmiana reguły detektora wymaga nowej wersji (`inventory-risk/2`) i wersjonowania workflow wariantu
  (`Workflow.getVersion`).

## Weryfikacja

- `EvidenceTest`: kanoniczny hash jest stabilny niezależnie od kolejności pól, trigger odrzuca zmianę, identyczna
  treść daje ten sam dowód, obcy sklep nie widzi dowodu ani snapshotu.
- `InventoryRiskDetectorTest`: jedna obserwacja nie otwiera epizodu; dwie w odstępie krótszym niż `min-gap` też
  nie; `UNKNOWN` przerywa serię; nieaktualny stan daje `UNKNOWN`.
- `OpportunityConcurrencyTest` (IG-05): dwa równoczesne otwarcia dają jeden epizod; niedozwolone przejście statusu
  jest odrzucane.
- Test workflow wariantu: timer drugiej obserwacji, jeden start `InventoryGuardianWorkflow` mimo powtórek.
