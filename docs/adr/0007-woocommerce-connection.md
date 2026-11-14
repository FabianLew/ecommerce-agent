# ADR-0007: Połączenie WooCommerce: sekrety, uwierzytelnianie i ochrona przed SSRF

- Status: Proponowany
- Data: 2026-11-14
- Powiązania: dokument 04 §10 (Integrations), ADR-0004 (zakres sklepu), ADR-0005 (dostęp do danych), backlog B-12

## Kontekst

B-12 podłącza istniejący sklep WooCommerce przez klucz REST API (consumer key i consumer secret). To pierwszy
sekret klienta w systemie i pierwsze wywołania sieciowe pod adres podany przez użytkownika. Decyzje o
przechowywaniu sekretów, sposobie uwierzytelnienia i ograniczeniu adresów są trudne do odwrócenia, bo
dotyczą danych, które zapiszemy w bazie, i powierzchni ataku na backend.

Ograniczenia:

- Sekrety nigdy nie wracają z backendu, nie trafiają do logów, audytu ani do przeglądarki po zapisie (04 §10).
- Lokalny sklep testowy działa po HTTP (`http://localhost:8081`). WooCommerce przyjmuje HTTP Basic tylko przez
  HTTPS. Przez HTTP wymaga podpisu OAuth 1.0a.
- Adres sklepu podaje użytkownik, więc backend nie może stać się proxy do sieci wewnętrznej (SSRF).
- KMS i hosting produkcyjny wybieramy w F4. Rozwiązanie z B-12 musi dać się przenieść bez migracji formatu.

## Decyzja

1. **Model.** Tabela `integration_connection` (jedno połączenie danego dostawcy na sklep: `UNIQUE (store_id,
   provider)`, na start tylko `WOOCOMMERCE`) przechowuje adres, status i wynik ostatniego testu. Sekrety są w
   osobnej tabeli `integration_credential` (połączenie, nazwa, szyfrogram, nonce, identyfikator klucza). Listy i
   DTO czytają tylko pierwszą tabelę, więc zapytanie widoku nie może przypadkiem pobrać sekretu. Obie tabele mają
   złożony FK do sklepu (ADR-0004). Do wyświetlania zapisujemy tylko podpowiedź: ostatnie 4 znaki klucza.
2. **Szyfrowanie.** AES-256-GCM, losowy 96-bitowy nonce na każdy zapis, AAD = `connection_id|store_id|nazwa`
   (szyfrogramu nie da się przenieść do innego połączenia ani sklepu). Klucz 32 bajty w base64 ze zmiennej
   `INTEGRATION_SECRETS_KEY` z identyfikatorem `INTEGRATION_SECRETS_KEY_ID`. Bez klucza aplikacja nie startuje.
   Profil `local` ma jawny klucz deweloperski, który nigdy nie może trafić na inne środowisko. Kolumna `key_id`
   pozwala w F4 przejść na envelope encryption z KMS bez zmiany schematu, z ponownym zaszyfrowaniem rekordów.
3. **Uwierzytelnienie.** Przez HTTPS: HTTP Basic z kluczem i sekretem (zalecenie WooCommerce). Przez HTTP:
   OAuth 1.0a one-legged z HMAC-SHA256, dozwolone wyłącznie przy `operator.integration.woocommerce.allow-insecure-http=true`
   (tylko profil `local`). Poza tym adres `http://` jest odrzucany przy zapisie.
4. **Ochrona adresu (SSRF).** Adres sklepu: tylko `https` (lub `http` z flagą powyżej), bez danych logowania,
   zapytania i fragmentu, normalizowany do postaci `scheme://host[:port][/ścieżka]`. Przed każdym wywołaniem
   host jest rozwiązywany i odrzucany, jeśli którykolwiek adres jest loopback, prywatny, link-local, CGNAT
   (100.64/10), unique-local IPv6, multicast lub nieokreślony. Wyjątek: `allow-private-network=true` (tylko
   `local`). Klient nie podąża za przekierowaniami, ma limit połączenia 5 s, odczytu 10 s i rozmiaru odpowiedzi.
   Ścieżki API są stałe (`/wp-json/wc/v3/...`); użytkownik nie podaje ścieżki zasobu.
5. **Test połączenia.** Wyłącznie odczyty: `products?per_page=1`, `orders?per_page=1` i waluta sklepu z
   `settings/general/woocommerce_currency`. Wynik to status (`CONNECTED` albo `ERROR`) i kod błędu z krokiem
   naprawy: `AUTH_FAILED`, `PERMISSION_DENIED`, `UNREACHABLE`, `TLS_ERROR`, `NOT_WOOCOMMERCE`,
   `ADDRESS_NOT_ALLOWED`, `UNEXPECTED_RESPONSE`. Niezgodność waluty z ustawieniem sklepu jest ostrzeżeniem.
   Test działa synchronicznie w żądaniu (3 odczyty z limitami czasu) i zwraca wynik od razu. To świadome
   uproszczenie wobec 04 §10 (202 + operation ID). Na asynchroniczny test przejdziemy, gdy pojawi się dostawca
   z dłuższym procesem (Meta, poczta).
6. **API** (zgodnie z 04 §10, B = `/api/v1/stores/{storeId}`): `GET B/integrations`,
   `POST B/integration-connections` `{provider, storeUrl}`, `POST B/integration-connections/{id}/credentials`
   `{consumerKey, consumerSecret}` i `POST B/integrations/{id}/checks`. Odczyt wymaga `STORE_READ`, zmiany
   `STORE_MANAGE`; zapis sekretów woła `StoreAccess.reauthorize` tuż przed zapisem. Każda zmiana i test zapisują
   zdarzenie audytu bez sekretów. Nowe sekrety albo nowy adres cofają status do `CHECK_REQUIRED`.
   Synchronizacja (`sync-jobs`) przychodzi w B-13, odłączenie (`disconnect`) później.
7. **Uprawnienia klucza.** Wystarczy klucz `read`. Odczytowy test nie potrafi bezpiecznie wykryć, że klucz ma
   też zapis, więc UI prosi o klucz tylko do odczytu, a backend nigdy nie wykonuje zapisów do Woo w Pilocie.
8. **Frontend.** Formularz w Integrations wysyła sekrety przez Server Action do backendu w treści POST.
   Pola typu `password` bez autouzupełniania; wartości nie są zapisywane w sesji, adresie ani pamięci aplikacji.

## Rozważane alternatywy

- **Sekrety w kolumnach `integration_connection`.** Prostsze, ale każde `SELECT *` lub nowe zapytanie listy
  może je pobrać. Odrzucone.
- **pgcrypto w bazie.** Klucz musiałby trafiać do SQL (parametry, logi zapytań). Odrzucone.
- **KMS od razu.** Wymaga decyzji hostingowej z F4. Kolumna `key_id` zostawia tę drogę otwartą.
- **Basic przez HTTP lokalnie.** WooCommerce go nie przyjmuje, a przyzwyczaja do wysyłania sekretu jawnym
  tekstem. Odrzucone na rzecz OAuth 1.0a pod flagą lokalną.
- **Flow autoryzacji WooCommerce (`/wc-auth/v1/authorize`).** Wygodniejszy dla użytkownika, ale wymaga
  publicznego callbacku i wyboru adresu backendu. Do rozważenia przy onboardingu (F6); format sekretów pozostaje.

## Konsekwencje

- Utrata `INTEGRATION_SECRETS_KEY` oznacza konieczność ponownego podania kluczy przez właścicieli sklepów.
  Rotacja klucza wymaga ponownego szyfrowania (narzędzie dopiero w F4).
- Sprawdzenie DNS przed wywołaniem zostawia okno na DNS rebinding między sprawdzeniem a połączeniem. W F4
  ruch wychodzący do sklepów przejdzie przez proxy egress z tą samą polityką adresów.
- Aktor systemowy importu (B-13) odczytuje sekrety w zakresie systemowym (`STORE_READ`). Odszyfrowanie jest
  dostępne tylko wewnątrz modułu `integration`, nie w jego publicznym API.

## Weryfikacja

- Testy jednostkowe: szyfrowanie (AAD wiąże szyfrogram z połączeniem i sklepem, podmiana nonce lub
  szyfrogramu jest wykrywana), podpis OAuth 1.0a (wektor testowy), walidacja adresów i blokada sieci prywatnych.
- Testy integracyjne API: izolacja sklepów, `STORE_MANAGE` dla zmian (ANALYST dostaje 403), sekret nie wraca
  w żadnej odpowiedzi ani w audycie, mapowanie błędów z atrapy serwera HTTP.
- Ręcznie i w CI-smoke: test połączenia z lokalnym WooCommerce (HTTP + OAuth 1.0a) zwraca `CONNECTED`.
