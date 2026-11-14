# ADR-0009: Webhooki WooCommerce: rejestracja, podpis, inbox, normalizacja i outbox

- Status: Zaakceptowany (wariant rejestracji ręcznej wybrany przez właściciela, 2026-11-14)
- Data: 2026-11-14
- Powiązania: dokument 01 §„Przepływ danych”, dokument 03 §3 i §11 (IG-04), dokument 04 §10, ADR-0004, ADR-0007,
  ADR-0008, backlog B-14 (B-15 i B-16 korzystają z outboxa)

## Kontekst

B-14 przyjmuje webhooki WooCommerce. To pierwszy publiczny endpoint bez OIDC: wywołuje go sklep, nie
użytkownik. Trudne do odwrócenia są tu cztery rzeczy: jak webhooki powstają w sklepie (wpływa na uprawnienia
klucza z ADR-0007 i na onboarding), jak rozpoznajemy sklep i weryfikujemy nadawcę, co zapisujemy (zamówienia w
webhooku zawierają dane klientów) i jak chronimy stan przed starymi zdarzeniami (IG-04).

Eksperyment na lokalnym WooCommerce 11.2.1 (webhooki przez wp-cli, odbiornik w sieci compose):

| Obserwacja | Wynik |
| --- | --- |
| Utworzenie aktywnego webhooka | POST `application/x-www-form-urlencoded` `webhook_id=N`, **bez podpisu** (ping) |
| Nagłówki dostawy | `X-WC-Webhook-Topic`, `-Resource`, `-Event`, `-ID`, `-Delivery-ID`, `-Source`, `-Signature` |
| Podpis | `base64(HMAC-SHA256(surowe body, sekret))`, zgodny z naszym wyliczeniem |
| Zmiana stanu wariacji | `product.updated` z obiektem wariacji (`type=variation`, `parent_id`), nowy `date_modified_gmt` |
| Zamówienie `processing` | `order.created`, `order.updated` (identyczne body) i `product.updated` produktu ze zmniejszonym stanem i nowym `date_modified_gmt` |
| Anulowanie zamówienia | `order.updated` i `product.updated` ze zwróconym stanem |
| Body zamówienia | pełne `billing`, `shipping`, `customer_ip_address`, `customer_user_agent` |
| Wersja API webhooka | wp-cli tworzy `wp_api_v2` (brak `stock_status` w produkcie); panel domyślnie `wp_api_v3` |
| Dostawa | `wp_safe_remote_request`: blokuje hosty prywatne i porty inne niż 80, 443, 8080; domyślnie asynchronicznie przez Action Scheduler |

## Decyzja

1. **Rejestracja ręczna, klucz API zostaje tylko do odczytu.** Tworzenie webhooków przez REST wymaga klucza z
   zapisem, a ADR-0007 zakłada wyłącznie odczyt. W Integracjach pokazujemy adres dostawy i listę tematów:
   `Order created`, `Order updated`, `Product created`, `Product updated`, wersja API „WP REST API Integration
   v3”. Użytkownik tworzy je w panelu Woo (Ustawienia → Zaawansowane → Webhooki) z jednym wspólnym sekretem.
   Sekret generuje przeglądarka (`crypto.getRandomValues`, 32 bajty), użytkownik wkleja go w Woo i wysyła do nas
   przez Server Action. Backend przechowuje go jak klucze API (`integration_credential`, nazwa `webhook_secret`,
   AES-GCM z ADR-0007) i nigdy go nie zwraca. Brakujący lub wyłączony webhook wykryje polling i rekonsyliacja
   (B-16), więc webhook jest przyspieszeniem, nie jedynym źródłem.
2. **Endpoint.** `POST /api/v1/webhooks/woocommerce/{endpointKey}`, bez OIDC. `endpointKey` to losowy,
   128-bitowy klucz połączenia (`integration_connection.webhook_endpoint_key`), nie identyfikator połączenia ani
   sklepu. Sklep i organizację bierzemy z połączenia znalezionego po kluczu, nigdy z payloadu ani nagłówków.
   Kolejność: rozmiar ≤ 1 MiB (inaczej 413) → klucz istnieje (inaczej 404) → ping (`webhook_id=N`,
   form-urlencoded) daje 200 bez zapisu, także przed zapisaniem sekretu → zapisany sekret i podpis HMAC-SHA256
   na surowych bajtach, porównanie w stałym czasie (inaczej 401) →
   temat z listy dozwolonej i JSON z polem `id` (inaczej 200 i brak zapisu, żeby Woo nie wyłączył webhooka po
   5 błędach) → zapis do inbox i 200. Body nie trafia do logów. To jedyne miejsce, gdzie zapytanie bez
   `TrustedStoreScope` szuka połączenia (po kluczu); dalej działa zakres systemowy `woo-webhook` z
   `StoreAccess.systemScope` (ADR-0004).
3. **Inbox z deduplikacją i minimalizacją.** Tabela `webhook_inbox`: połączenie, sklep, temat, ID webhooka i
   dostawy z Woo, `payload_sha256` (z surowego body), zminimalizowany payload, czas przyjęcia, status
   (`RECEIVED`, `PROCESSED`, `IGNORED`, `FAILED`), liczba prób, wynik. `UNIQUE (connection_id, topic,
   payload_sha256)`: powtórzona dostawa tego samego body to ten sam wiersz i 200 bez kolejnej mutacji. Zamiast
   surowego body zapisujemy tylko pola z listy dozwolonej, takie same jak w imporcie (ADR-0008): dla zamówień
   status, daty, kwoty, waluta i pozycje, dla produktów pola katalogu i stanu. Dane klienta są odrzucane przed
   zapisem. To świadome odstępstwo od „raw event” z dokumentu 03 §3; skrót surowego body wystarcza do
   deduplikacji i diagnozy.
4. **Normalizacja w workerze.** `WebhookInboxProcessor` (profil `worker`) co 2 s pobiera wiersze `RECEIVED`
   (`FOR UPDATE SKIP LOCKED`, paczki po 50). Dla każdego w jednej transakcji: aktualizacja danych handlu, zapis
   zdarzeń outbox i status wiersza. Błąd wycofuje transakcję i zwiększa licznik prób (ponowienie z odstępem do 5
   prób, potem `FAILED`, widoczne w Integration Health z B-16).
5. **Bez cofania stanu (IG-04).** Dla każdego wariantu z payloadu porównujemy `date_modified_gmt` z
   `source_updated_at` ostatniej obserwacji stanu:
   - starszy: zdarzenie przestarzałe, bez zapisu (`IGNORED`, wynik `STALE`);
   - równy i ten sam stan: duplikat, bez zapisu;
   - równy, ale inny stan (dwie zmiany w tej samej sekundzie): niejednoznaczne, bez zapisu i ze zdarzeniem
     `commerce.inventory.verification-requested.v1` (autorytatywny odczyt w B-15/B-16);
   - nowszy: nowa obserwacja `WEBHOOK` i `commerce.inventory.changed.v1`.
   Payload produktu bez `stock_status` (webhook w wersji v1/v2) nie zmienia stanu, tylko prosi o weryfikację.
   Produkt zmienny ze stanem na rodzicu aktualizuje warianty `PARENT`. Wariacja nieznanego produktu prosi o
   weryfikację katalogu. Zamówienia używają istniejącego upsertu z ochroną po `source_updated_at` i emitują
   `commerce.order.changed.v1` z listą wariantów. Webhooki nie rozszerzają `order_sync_coverage`: kompletność
   potwierdza tylko import albo rekonsyliacja z licznikiem (ADR-0008, B-16).
6. **Outbox.** Tabela `outbox_event`: sklep, typ zdarzenia, agregat (`variant`, `order`, `product`), payload
   v1, `dedup_key` unikalny w sklepie, czas utworzenia, wysłania i następnej próby. Klucz opisuje stan, nie dostawę:
   `inventory:{wariant}:{sourceUpdatedAt}:{stan}:{status}`, `order:{id}:{sourceUpdatedAt}:{status}`, a prośba o weryfikację
   `verify:{inbox}:{agregat}`. Ten sam stan z dwóch tematów (`order.created` i `order.updated`) albo ponowne
   przetworzenie wiersza inbox nie tworzy drugiego zdarzenia. Wysyłkę do Temporal (signalWithStart z
   deterministycznym ID) dodaje B-15; do tego czasu zdarzenia czekają z `dispatched_at = null`.
7. **Lokalnie.** Woo w Dockerze dostarcza na `http://host.docker.internal:8080` (wpis `host-gateway` w compose).
   Seed instaluje mu-plugin dopuszczający hosty prywatne (`http_request_host_is_external`) tylko w lokalnym
   compose. Adres publiczny backendu podaje `operator.integration.webhook-base-url`.

## Rozważane alternatywy

- **Klucz z zapisem i automatyczna rejestracja przez `POST /webhooks`.** Najwygodniejsze, ale łamie zasadę
  najmniejszych uprawnień z ADR-0007 dla każdego klienta. Do ponownej oceny przy onboardingu (F6), razem z
  flow `/wc-auth`. Odrzucone w Pilocie.
- **Sekret generowany przez backend i pokazany raz.** Wygodne, ale backend zwracałby sekret wbrew 04 §10.
  Odrzucone na rzecz sekretu z przeglądarki.
- **Identyfikator połączenia w adresie.** Ujawnia identyfikator wewnętrzny i nie da się go zmienić bez
  usunięcia połączenia. Odrzucone na rzecz osobnego klucza.
- **Normalizacja w żądaniu HTTP.** Szybsza ścieżka, ale druga droga obok ponowień z inbox. Woo i tak dostarcza
  asynchronicznie. Odrzucone.
- **Deduplikacja po `X-WC-Webhook-Delivery-ID`.** Woo nadaje nowy identyfikator przy każdej dostawie, więc
  ręczne „wyślij ponownie” i identyczne `order.created`/`order.updated` nie byłyby duplikatami. Zapisujemy go
  tylko do diagnozy.
- **Ufanie kolejności przyjęcia.** Woo nie gwarantuje kolejności (Action Scheduler, ponowne wysłanie). Odrzucone.

## Konsekwencje

- Onboarding ma jeden ręczny krok więcej (4 webhooki w panelu Woo). UI musi go jasno prowadzić.
- `date_modified_gmt` ma dokładność sekundy; dwie zmiany w jednej sekundzie dają weryfikację zamiast zapisu.
- Endpoint publiczny przyjmuje ruch bez uwierzytelnienia do momentu sprawdzenia podpisu. Limit rozmiaru i
  wyszukiwanie po indeksie ograniczają koszt; limit częstości i proxy brzegowe przyjdą w F4.
- Webhook z niepoprawnym sekretem po stronie Woo kończy się 401, a Woo po 5 błędach wyłącza webhook. Health
  (B-16) pokaże brak zdarzeń mimo zmian wykrytych przez polling.
- Inbox i outbox rosną; retencja (np. 30 dni dla przetworzonych) wejdzie razem z retencją audytu w F4.

## Weryfikacja

- Eksperyment z tabeli powyżej (wp-cli i odbiornik w sieci compose).
- `WooWebhookApiTest`: ping, zły i brakujący podpis (401), nieznany klucz (404), za duże body (413), duplikat
  dostawy (jeden wiersz), brak danych klienta w inbox, izolacja sklepów.
- `WebhookNormalizationTest` (IG-04): stary event po nowym, duplikat, ta sama sekunda z innym stanem,
  wariacja `PARENT`, zamówienie starsze od zapisanego, jedno zdarzenie outbox przy ponownym przetworzeniu.
- Ręcznie: webhooki z lokalnego Woo do backendu przez `host.docker.internal`, zmiana stanu widoczna jako
  obserwacja `WEBHOOK`.
