# ADR-0010: Przekazywanie zdarzeń do Temporal, polling i rekonsyliacja WooCommerce, Integration Health

- Status: Proponowany
- Data: 2026-11-14
- Powiązania: dokument 01 §„Przepływ danych”, dokument 03 §3 i §11, dokument 04 §10, ADR-0004, ADR-0008,
  ADR-0009, backlog B-15, B-16

## Kontekst

B-14 zapisuje zdarzenia domenowe w `outbox_event`. B-15 ma je przekazać do Temporal tak, żeby powtórka nie
tworzyła drugiego workflow. B-16 ma uzupełnić webhooki pollingiem i rekonsyliacją, prowadzić watermarki i
pokazać zdrowie integracji, a rekonsyliacja ma wykryć zgubiony webhook. Trudne do odwrócenia są: identyfikatory
workflow (od nich zależy deduplikacja i przyszły epizod Inventory Guardian), co uznajemy za autorytatywny
odczyt stanu, semantyka watermarków i kiedy integracja blokuje analizę jako nieaktualna.

## Decyzja

1. **Dispatcher (B-15).** `OutboxDispatcher` w profilu `worker` co 1 s blokuje paczkę niewysłanych zdarzeń
   (`FOR UPDATE SKIP LOCKED`, 100), dla każdego wykonuje `signalWithStart` i w tej samej transakcji ustawia
   `dispatched_at`. Błąd Temporal zwiększa `attempts` i odkłada zdarzenie (`next_attempt_at`, do 1 h). Gdy
   sygnał przeszedł, a commit nie, zdarzenie pójdzie drugi raz: workflow odrzuca znany `eventId`.
2. **Deterministyczne identyfikatory.**
   - `inventory-{storeId}-{variantId}`: `VariantInventoryWorkflow`, długotrwały, jeden na wariant. Dostaje
     `inventory.changed`, `inventory.verification-requested` i `order.changed` (jeden sygnał na każdy wariant
     z listy, `eventId:variantId`). Stąd w I7 wystartuje epizod Inventory Guardian.
   - `store-sync-{connectionId}`: `StoreSyncWorkflow`, prośby o weryfikację katalogu (`product`).
   `signalWithStart` na działający workflow tylko dodaje sygnał, więc powtórzone zdarzenie nie tworzy drugiego
   workflow. Workflow pamięta ostatnie 500 identyfikatorów zdarzeń i przenosi je przez `continueAsNew` (co 500
   sygnałów), więc historia pozostaje mała.
3. **Autorytatywny odczyt wariantu.** `VariantInventoryWorkflow` po prośbie o weryfikację, a także po
   `order.changed`, jeśli w ciągu 60 s nie przyszedł nowszy stan wariantu, czyta wariant (albo produkt
   nadrzędny przy `PARENT`) prosto z Woo i zapisuje obserwację `POLL`. Odczyt świeży jest prawdą w chwili
   odczytu, więc zapisujemy go także przy tej samej sekundzie co ostatni zapis (rozstrzyga `AMBIGUOUS`). Nie
   zapisujemy tylko identycznego stanu. Kilka próśb w oknie 60 s daje jeden odczyt.
4. **Polling (B-16).** Temporal Schedule `woo-poll-{connectionId}` co 5 min uruchamia `IncrementalSyncWorkflow` (rodzaj `POLL`):
   `products?modified_after={watermark − 2 min}` oraz wariacje zmienionych produktów zmiennych. Zmiany zapisuje jak
   odczyt autorytatywny. Watermark `PRODUCTS` (największy `date_modified_gmt`) przesuwa się dopiero po
   przejściu wszystkich stron.
5. **Rekonsyliacja (B-16).** Schedule `woo-reconcile-{connectionId}` co godzinę: wszystkie zamówienia
   `modified_after={watermark ORDERS − 2 min}`, bez górnej granicy, z porównaniem `X-WP-Total` jak w ADR-0008.
   Zamówienie złożone przed startem przebiegu ma datę modyfikacji nie wcześniejszą niż złożenia, więc pełny
   odczyt obejmuje wszystkie zamówienia złożone do startu. Górna granica (`modified_before`) zrobiłaby dziurę:
   zamówienie złożone przed startem i zmienione w trakcie przebiegu wypadłoby z odczytu. Przy zgodności
   licznika przedłużamy `order_sync_coverage` do startu przebiegu i przesuwamy tam watermark. Przy niezgodności
   nic nie przesuwamy, a health pokazuje rosnące opóźnienie kompletności. Nocny przegląd całego katalogu (pełny
   stan) zostaje na później.
6. **Zgubiony webhook.** Polling porównuje każdą zmianę z ostatnią obserwacją. Jeśli połączenie ma zapisany
   sekret webhooków, a zmiana z Woo jest nowsza od naszego stanu i starsza niż 2 minuty, liczymy ją jako
   zgubiony webhook. Liczba trafia do przebiegu (`sync_check.missed_webhooks`). Zamówienia z rekonsyliacji,
   których nie było w bazie albo były w starszym stanie, liczą się tak samo. Zmiana sprzed zapisania sekretu
   się nie liczy.
7. **Integration Health** (pole `health` w `GET /stores/{storeId}/integrations`), liczone przy odczycie:
   - `NOT_READY`: brak udanego importu początkowego;
   - `STALE`: polling bez sukcesu od 15 min albo kompletność zamówień starsza niż 2 h. Blokuje analizę (03 §11);
   - `DEGRADED`: zgubione webhooki w ostatnich 24 h, brak sekretu webhooków, wpisy `FAILED` w inbox albo
     zaległy outbox starszy niż 5 min;
   - `HEALTHY`: pozostałe przypadki.
   Każdy powód ma kod i krok naprawy w UI.
8. **Harmonogramy.** Worker przy starcie i co minutę tworzy (idempotentnie) brakujące Schedule dla połączeń
   `CONNECTED` z udanym importem, więc sklep dostaje polling najpóźniej minutę po imporcie. Kontekst sklepu
   (aktor `woo-sync`) jest zapisany w nagłówku akcji Schedule, a pierwszy przebieg startuje od razu. Polityka nakładania: `SKIP`, okno nadrabiania 10 min. Interwały
   są parametrami pilota (`operator.integration.woocommerce.poll-interval`, `reconcile-interval`).
9. **Aktor.** Workflow działają jako aktor systemowy `woo-sync` z zakresem `STORE_READ` (ADR-0004). Zakres
   pochodzi z nagłówków startu, a interceptor aktywności weryfikuje go w bazie.

## Rozważane alternatywy

- **Jeden workflow na sklep dla wszystkich zdarzeń.** Mniej workflow, ale wąskie gardło i długa historia.
  Detektor ryzyka działa per wariant (03 §3). Odrzucone.
- **Workflow per zdarzenie (ID = eventId).** Łatwa deduplikacja, ale brak miejsca na stan wariantu i
  debounce. Odrzucone.
- **Polling bez `modified_after`, pełny katalog co 5 min.** Proste, ale koszt rośnie z katalogiem i limitami
  hostingu sklepu. Pełny przegląd zostaje na noc, później.
- **Cron w Springu zamiast Temporal Schedules.** Brak historii przebiegów, ponowień i pauzy per sklep.
  Odrzucone (dokument 01: Temporal dla procesów w tle).

## Konsekwencje

- Liczba workflow rośnie z liczbą wariantów, które mają zdarzenia. Długotrwałe workflow wymagają wersjonowania
  (`Workflow.getVersion`) przy każdej zmianie logiki, jak w B-08.
- Serwer testowy Temporal nie obsługuje Schedules. Testy wołają workflow bezpośrednio, a tworzenie
  harmonogramów sprawdzamy na lokalnym Temporal (CI-smoke).
- `modified_after` wymaga WooCommerce ≥ 5.8. Sklepy ze starszą wersją dostaną błąd w teście połączenia.

## Weryfikacja

- `OutboxDispatchTest`: powtórzone zdarzenie i powtórny dispatch dają jeden workflow i jeden obsłużony
  sygnał; `order.changed` trafia do workflow każdego wariantu; błąd Temporal odkłada zdarzenie.
- `VariantInventoryWorkflowTest`: debounce weryfikacji, odczyt autorytatywny rozstrzyga `AMBIGUOUS`,
  `continueAsNew` zachowuje identyfikatory, test replay z zapisaną historią.
- `IncrementalSyncTest` z atrapą Woo: watermark tylko po komplecie stron, wykrycie zgubionego
  webhooka, przedłużenie pokrycia tylko przy zgodnym liczniku.
- `IncrementalSyncTest`: przejścia health `NOT_READY` → `STALE` → `DEGRADED` → `HEALTHY` → `DEGRADED` → `STALE`.
