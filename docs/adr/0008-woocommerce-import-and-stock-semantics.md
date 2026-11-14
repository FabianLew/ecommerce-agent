# ADR-0008: Import WooCommerce (14 dni) i semantyka stanów magazynowych

- Status: Zaakceptowany (akceptacja właściciela, 2026-11-14)
- Data: 2026-11-14
- Powiązania: dokument 03 (Inventory Guardian, IG-02, IG-25), ADR-0004, ADR-0007, backlog B-13, B-13b

## Kontekst

Pierwszy Inventory Guardian potrzebuje katalogu, aktualnych stanów i zamówień z co najmniej 7 pełnych dni
lokalnych. Korekta po akceptacji planu: nie blokujemy go na pełnej historii, więc B-13 importuje minimalne okno,
a pełną historię (12 miesięcy) dociągnie B-13b. Dwie decyzje są trudne do odwrócenia: jak interpretujemy
`stock_quantity` z WooCommerce (od tego zależy dostępny stan i ryzyko podwójnego odejmowania rezerwacji, IG-02)
oraz kiedy uznajemy okres zamówień za kompletny (od tego zależy, czy metryki w ogóle się liczą).

## Decyzja

1. **Semantyka stanu: `ALREADY_ACCOUNTED_FOR`.** Eksperyment na lokalnym WooCommerce 11.2.1 (produkt z
   `manage_stock`, stan 49, wp-cli):

   | Zdarzenie | `stock_quantity` |
   | --- | --- |
   | zamówienie `pending` (2 szt.) | 49 (bez zmian) |
   | → `on-hold` | 47 |
   | → `processing`, → `completed` | 47 |
   | → `cancelled` | 49 (zwrot na stan) |
   | nowe zamówienie od razu `processing` (3 szt.) | 46 |
   | → `refunded` | 46 (bez automatycznego zwrotu) |
   | → `failed` | 49 (zwrot na stan) |

   Stan WooCommerce już uwzględnia zamówienia `on-hold`, `processing` i `completed`. Rezerwacje koszyka z
   checkoutu (`wc_reserved_stock`) nie są w REST API, więc `reserved_quantity` zostaje puste. Dostępny stan =
   `stock_quantity`, bez odejmowania czegokolwiek (IG-02).
2. **Śledzenie stanu.** Produkt prosty jest swoim jedynym wariantem. Wariacja z `manage_stock=true` →
   `VARIANT`, `manage_stock="parent"` → `PARENT` (ilość z produktu nadrzędnego), `false` → `NONE` (brak ilości,
   tylko status). Produkt zmienny sam nie jest jednostką sprzedaży.
3. **Okno importu.** Od początku dnia lokalnego sklepu 14 dni temu do chwili startu importu. Zapytanie:
   `orders?after=…&before=…&dates_are_gmt=true&orderby=date&order=asc&status=any`, strony po 100.
4. **Kompletność.** Okres trafia do `order_sync_coverage` tylko wtedy, gdy liczba pobranych zamówień równa
   się `X-WP-Total` z pierwszej strony, a wszystkie strony przeszły. Inaczej przebieg kończy się jako
   `INCOMPLETE` i metryki dla tego okresu zwrócą brak danych zamiast zaniżonej sprzedaży.
5. **Minimalizacja danych.** Zamówienia pobieramy z `_fields` ograniczonym do statusu, dat, kwot, waluty i
   pozycji. Dane klienta (adresy, e-mail, telefon) nie opuszczają WooCommerce.
6. **Wykonanie.** Workflow Temporal `WooImportWorkflow` (moduł `automation`) z aktywnościami katalogu i
   zamówień. Startuje go komenda użytkownika z `STORE_MANAGE` (`POST B/integrations/{id}/sync-jobs`), a działa
   jako aktor systemowy `woo-import` z zakresem `STORE_READ`: import zapisuje dane źródłowe sklepu, nie zmienia
   niczego w sklepie ani w politykach. Kto uruchomił import, zapisuje audyt i `sync_run`. Jeden aktywny import na
   sklep (unikalny indeks i identyfikator workflow).

## Rozważane alternatywy

- **Odejmowanie zamówień `processing` od stanu.** Daje podwójne odejmowanie (IG-02) wg eksperymentu. Odrzucone.
- **Uznawanie okresu za kompletny bez porównania licznika.** Szybsze, ale zamówienia dodane lub usunięte w
  trakcie stronicowania zaniżyłyby metryki bez śladu. Odrzucone.
- **Import w żądaniu HTTP.** Brak ponowień i postępu, ryzyko limitu czasu przy większych sklepach. Odrzucone.

## Konsekwencje

- Zwroty (`refunded`) nie przywracają stanu automatycznie; metryki sprzedaży (B-17) liczą tylko statusy
  kwalifikowane, więc zwrócone zamówienie nie zawyża sprzedaży, ale stan może być niższy niż fizyczny.
- Zamówienie zmienione w trakcie importu może trafić z nowszym stanem w kolejnym przebiegu (upsert po
  `date_modified_gmt`). Przyrostowy polling (B-14) domknie to bez ponownego pełnego okna.
- Eksperyment dotyczy WooCommerce 11.2; wtyczki zmieniające rezerwacje mogą działać inaczej. Pilot sprawdzi
  to na prawdziwym sklepie przed włączeniem rekomendacji.

## Weryfikacja

- Eksperyment z tabeli powyżej (wp-cli na lokalnym sklepie z `infra/docker-compose.yml`).
- `WooCommerceImportTest`: stronicowanie, warianty i `manage_stock=parent`, minimalizacja pól, brak pokrycia
  przy niezgodnym liczniku, idempotencja ponownego importu, izolacja sklepów.
- `WooImportWorkflowTest`: kolejność aktywności, oznaczenie porażki i niekompletności.
- Ręcznie: import lokalnego sklepu przez UI, liczby produktów, wariantów i zamówień zgodne z WooCommerce.
