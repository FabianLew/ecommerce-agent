# Autonomous E-commerce Operator — specyfikacja UI/UX MVP

Wersja: 1.1 · Data: 14 listopada 2026 · Status: specyfikacja do przeglądu

Źródła prawdy: [01 — zakres i decyzje produktu](01-PRODUCT-VISION-AND-ARCHITECTURE.md), [02 — kontrakty i architektura agentowa](02-AI-AGENT-ARCHITECTURE.md), [03 — referencyjny Inventory Guardian](03-INVENTORY-GUARDIAN-E2E.md). Obowiązuje ich legenda **[D] / [U] / [P] / [Z] / [O]**. Nowe decyzje produktowe D-04–D-07 są wiążącym zakresem; układy, komponenty, endpointy i DTO poniżej to **[P]**. Nie są deklaracją istniejącego API ani gotowego wdrożenia.

Wersja 1.1 uwzględnia D-08/D-09. Wymagania Decision Intelligence uzupełnia sekcja 17; definicje wiedzy, profilu, KPI i outcome mają jedno miejsce opisu w [05 — Decision Intelligence Architecture](05-DECISION-INTELLIGENCE-ARCHITECTURE.md).

## 1. Cel i zasady UX [D, P]

**Simple by default. Powerful by design.** Użytkownik ma szybko ustalić: jak działa sklep, co zrobił operator AI i co wymaga decyzji człowieka. Pełna analityka jest częścią MVP, dostępna zarówno z nawigacji, jak i z konkretnej decyzji. Nie tworzymy przełącznika początkujący/ekspert. Różnice dostępnych akcji wynikają z uprawnień i polityk, nie z poziomu znajomości analityki.

Progressive disclosure ma trzy poziomy:

| Poziom | Prezentacja | Źródło |
|---|---|---|
| 1. Co się dzieje i co zrobić | Krótkie podsumowanie, istotne liczby, proponowana czynność i pilność | Business Summary |
| 2. Dlaczego | Uzasadnienie, zakres akcji, konsekwencje, ryzyka, świeżość i polityka | Decision Explanation |
| 3. Sprawdź dane | Wykresy, tabele, porównania, definicje i źródła | Analytical Evidence |

Liczby wszystkich poziomów pochodzą ze wspólnych dowodów i obliczeń, zgodnie z [reprezentacjami decyzji](02-AI-AGENT-ARCHITECTURE.md#15-reprezentacje-decyzji-dla-ui-d-p). Rozwinięcie nie może ukrywać krytycznego ostrzeżenia: brak danych, niepewny wynik lub zakres zmiany są widoczne przed akceptacją. Nie pokazujemy chain-of-thought. Nazwy interfejsu są polskie; domenowe identyfikatory zachowują nazwy kontraktów.

## 2. Nawigacja, układ i komponenty [P]

### Mapa ośmiu widoków

Wszystkie ścieżki poniżej mają prefiks `/stores/:storeId`.

| Pozycja lewego sidebaru | Ścieżka | Szczegóły | Moduł backendu |
|---|---|---|---|
| Przegląd | `/dashboard` | Karta problemu, wykonana praca | Reporting / Commerce / Support |
| Decyzje | `/decisions` | `/decisions/:proposalId` | Intelligence / Automation |
| Obsługa klientów | `/support` | `/support/:conversationId` | Customer Support |
| Analityka | `/analytics/:tab` | `sales`, `profitability`, `ads`, `products` i drill-down | Reporting / Commerce |
| Automatyzacje | `/automations` | `/automations/:automationId` | Automation |
| Aktywność | `/activity` | `/activity/:activityId` | Audit / Reporting |
| Integracje | `/integrations` | `/integrations/:integrationId` | Integrations |
| Ustawienia | `/settings/:section` | Store, policies, team, notifications, data | Tenants / Identity / Policies |

Onboarding jest osobną ścieżką `/onboarding`, a nie dziewiątym codziennym widokiem. Nie dodajemy kreatora sklepu. Sidebar zawiera wybór istniejącego sklepu, osiem pozycji, liczniki oczekujących decyzji i eskalacji oraz stan operatora. Liczniki pochodzą z backendu i respektują dostęp do danych.

Nagłówek zawiera breadcrumbs, nazwę sklepu, strefę czasu, filtr okresu na ekranach analitycznych i wskaźnik połączeń. Filtry operacyjne skrzynki, np. „wymaga człowieka”, pozostają niezależne od dat wykresów. Ustawienia konta nie stają się dodatkowym trybem UI.

**Kontekst przejścia:** URL przechowuje zakres `from`, `to` (koniec wyłączny), `timezone`, porównanie oraz dopuszczone identyfikatory produktu/kampanii i źródła decyzji. Wsparcie wyszukiwania e-maili i treść wiadomości nie trafiają do udostępnianych URL. Powrót przywraca zakładkę, filtry, paginację i scroll. Zmiana sklepu czyści identyfikatory zasobów poprzedniego sklepu; link do obcego zasobu zwraca bezpieczny brak dostępu, nie przełącza tenanta automatycznie.

### Kierunek wizualny i design system

Minimalistyczny, jasny SaaS: tło `#F6F8F7`, powierzchnie `#FFFFFF`, tekst `#172B25`, tekst pomocniczy `#52665E`, akcent `#16734B`, delikatne zielone tło `#EAF5EE`, obramowanie `#DFE7E2`. Zieleń oznacza główną akcję i sukces, nie wszystkie dodatnie wartości. Bursztyn sygnalizuje uwagę, czerwień błąd; status ma również tekst i ikonę. Paleta jest propozycją wymagającą pomiaru kontrastu przed implementacją.

Typografia: Inter lub systemowy fallback, body 16 px/1,5, tabele 14 px, etykiety pomocnicze co najmniej 12 px, nagłówek strony 28 px; liczby tabelaryczne wyrównane do prawej. Skala odstępów 4/8/12/16/24/32 px, promień kart 12 px. Sidebar około 232 px, siatka treści z odstępem 24 px. Jedna główna akcja w kontekście; „Zatwierdź” opisuje zakres, np. „Zatwierdź pauzę reklamy”. Nazwa produktu pozostaje Autonomous E-commerce Operator; nazwa „Commerce OS” z prototypu nie jest zatwierdzonym brandingiem.

Desktop: pełny sidebar i tabele; tablet: zwijana nawigacja i węższe kolumny; telefon: drawer nawigacji, karty w jednej kolumnie, konwersacja jako oddzielny ekran. Tabele zachowują kluczowe pola i kontrolowany poziomy scroll, nie tracą danych przez ukrycie kolumn bez możliwości ich odczytu. Detale o trwałym adresie otwieramy jako stronę; drawer może służyć szybkiemu podglądowi. Widok Support na małym ekranie przechodzi lista → rozmowa → kontekst.

**Komponenty React/Next.js:** `AppShell`, `StoreSwitcher`, `Breadcrumbs`, `DateRangeFilter`, `MetricCard`, `QualityBadge`, `FreshnessIndicator`, `TrendChart`, `DataTable`, `FilterBar`, `DecisionCard`, `ExplanationPanel`, `EvidenceDrawer`, `ExecutionTimeline`, `ConversationList`, `MessageTimeline`, `ReplyComposer`, `HandoffPanel`, `AutonomyControl`, `PolicyPreview`, `IntegrationStatusCard`, `StatePanel`, `ConfirmActionDialog`. To wspólna biblioteka UI; stan workflow pozostaje po stronie backendu. Klucze cache uwzględniają sklep, permissions, filtry i wersję danych.

## 3. Wspólne kontrakty frontend–backend [P]

### Konwencje transportowe

W tabelach endpointów `B = /api/v1/stores/{storeId}`. Tenant pochodzi z uwierzytelnionego principal, a backend sprawdza dostęp do sklepu i każdego zasobu. Integracyjne sekrety nie wracają w DTO. Paginacja list używa `cursor`, `limit`, zwraca `items`, `nextCursor`, `hasMore` oraz osobno wyliczane liczniki. Sortowanie i filtry są allowlistowane; frontend nie przesyła SQL.

Każda odpowiedź odczytu zawiera `meta`: `requestId`, `generatedAt`, `asOf`, `timezone`, `dataVersion`, `completeness`, `warnings`; części danych mogą mieć własne źródła i czasy. Edytowalne zasoby zwracają `version`/ETag. Pole `allowedActions` służy prezentacji, ale serwer ponownie autoryzuje komendę.

Komendy wysyłają `Idempotency-Key`; operacje zależne od stanu również `If-Match` aktualnej wersji. Wysłanie klucza drugi raz z innym payloadem jest konfliktem. Odpowiedź `202` zwraca `operationId`, `statusUrl`, ewentualny `actionId`; UI odczytuje `B/operations/{operationId}`, dopóki nie ma wyniku lub terminu eskalacji. `200/201` potwierdza zapis danego zasobu, nie implikuje zewnętrznego skutku. Nie stosujemy optymistycznego oznaczania mutacji jako wykonanej.

| HTTP / kod domenowy | Reakcja UI |
|---|---|
| 401 / brak sesji | Ponowne logowanie, zachowanie bezpiecznego kontekstu nawigacji |
| 403 / brak uprawnień | Wyjaśnienie ograniczenia, bez sugerowania retry nadającego prawa |
| 404 | Zasób niedostępny; bez ujawniania cudzych identyfikatorów |
| 409 lub 412 / `VERSION_CONFLICT`, `OWNERSHIP_CHANGED` | Odświeżenie i pokazanie zmiany; brak automatycznego ponowienia komendy |
| 410 / `PROPOSAL_EXPIRED` | Przycisk zgody nieaktywny, możliwość zlecenia nowej analizy |
| 422 / `POLICY_DENIED`, walidacja | Czytelna przyczyna przy polu lub akcji; brak obejścia przez klienta |
| 429 / limit | Informacja o oczekiwaniu zgodnie z retry-after |
| 503 / źródło niedostępne | Dane historyczne ze znacznikiem nieaktualności i blokada zależnej mutacji |

Błąd ma strukturę `{code, message, fieldErrors, retryable, requestId, currentVersion}`; opcjonalne pola występują tylko, gdy mają zastosowanie. Toast nie jest jedynym miejscem pokazania ważnego błędu. Backend zwraca status i bezpieczny opis, a nie surowe wyjątki dostawcy.

### DTO współdzielone

| DTO | Minimalne pola i znaczenie |
|---|---|
| `MetricDTO` | `key`, `value` lub `null`, `unit`, `currency`, `quality`, `period`, `definitionVersion`, `sourceRefs`, `observedAt`, `missingComponents`, `comparison` |
| `ComparisonDTO` | Wartość okresu bazowego, różnica bezwzględna i procentowa; przy zerowej bazie procent `null` z przyczyną |
| `AnalysisContext` | Store, daty, strefa, filtry produktu/kampanii, porównanie, `sourceProposalId`, opcjonalny `snapshotId` |
| `DecisionPresentation` | `proposalId`, `revision`, `status`, `businessSummary`, `explanation`, `evidenceRefs`, `analysisLinks`, `policyEvaluation`, `expiresAt`, `execution`, `allowedActions`, ETag |
| `OperationDTO` | ID, typ, `PENDING/RUNNING/SUCCEEDED/FAILED/UNKNOWN`, referencja do zasobu, status domenowy, reason i następna dozwolona akcja |

`quality` ma wartości z [modelu danych produktu](01-PRODUCT-VISION-AND-ARCHITECTURE.md#6-model-biznesowy-danych-u-p). `UNAVAILABLE` oznacza `value=null`, nigdy zero. Kwoty i zasady czasu stosują kontrakt tego modelu. Definicje metryk, a nie LLM, określają mianowniki, kwalifikujące się zamówienia i alokacje. Operacyjny sukces komendy jest odróżniony od stanu biznesowego: `SUCCEEDED` dla takeover nie oznacza wysłania odpowiedzi, a przyjęcie wiadomości przez dostawcę nie oznacza jej przeczytania.

### Stany wspólne i aktualizacje

Każdy widok ma opisane niżej loading/empty/error/success; dodatkowo `stale`, częściowe dane, brak uprawnień i akcję w toku. Loading stosuje skeleton o docelowym układzie. Empty odróżnia „brak zdarzeń” od „jeszcze nie zsynchronizowano”. Success oznacza wynik z potwierdzonego odczytu. Przy częściowej awarii zachowujemy poprawne sekcje, a uszkodzone oznaczamy lokalnie.

W MVP proponujemy polling otwartych operacji i konwersacji z backoffem; odświeżenie nie nadpisuje lokalnie edytowanego szkicu. **O-15:** SSE jako późniejsza optymalizacja albo wymóg pilota po pomiarze opóźnień. Każdy mechanizm aktualizacji zachowuje wersjonowanie. Tła nie przeładowują tabel bez zachowania zaznaczenia i fokusu.

## 4. Dashboard

**Cel i scenariusze:** szybka ocena kondycji sklepu, wykonanej pracy AI i wyjątków wymagających człowieka. Użytkownik otwiera decyzję, eskalację lub KPI i przechodzi do właściwego kontekstu.

**Układ i dane:** nagłówek z okresem i porównaniem; cztery karty: sprzedaż netto, zamówienia, wydatek Meta, marża kontrybucyjna po znanych kosztach. Dalej trend sprzedaży, „Wymaga uwagi”, „Co zrobił operator”, blok Support (sprawy obsłużone przez AI / eskalacje), zdrowie integracji i jakość danych. Karta rentowności ma widoczną etykietę estymacji/braku kosztów. Każdy licznik Support ma określone okno i jednostkę: liczba spraw, nie zamiennie liczba wiadomości.

**Akcje i nawigacja:** klik KPI → odpowiednia zakładka Analytics z tym samym okresem; karta ryzyka → Decision Detail; eskalacja → Conversation Detail; błąd źródła → konkretna integracja; ostatnie działanie → Activity detail. Dashboard nie ma zbiorczego „zaakceptuj wszystko” ani ukrytego włączania L2.

**Stany:** loading — niezależne skeletony kart; empty — po konfiguracji brak danych/zdarzeń, CTA do integracji lub zmiany dat; error — błąd sekcji i retry, pozostałe sekcje zostają; success — aktualne liczby i wykonane operacje; stale — pasek „Dane z…” i wpływ na dostępność analiz. „Brak spraw wymagających uwagi” jest pozytywnym empty, nie błędem.

**Backend i API:** Reporting agreguje dane ze wspólnych read models, bez inicjowania nowych analiz LLM przy wejściu na stronę.

| Endpoint | Wejście → wynik |
|---|---|
| `GET B/dashboard` | `from,to,timezone,compare` → `metrics: MetricDTO[]`, `salesTrend`, `attentionItems`, `recentActivities`, `supportSummary`, `integrationHealth`, meta |

`attentionItems` zawierają typ, ID zasobu, prosty tytuł, pilność, status i backendowy link; nie pełne PII ani treść e-mail. Metryki i wykres pochodzą z tej samej wersji danych lub UI jawnie pokazuje różnicę ich czasu odczytu.

## 5. Decision Inbox + Decision Detail

**Cel i scenariusze:** uporządkować decyzje biznesowe, zrozumieć powód i zakres, sprawdzić dane, zaakceptować/odrzucić ważną rewizję, śledzić wykonanie. Rozmowy z klientami mają osobną kolejkę Support; tu może istnieć powiązana decyzja biznesowa.

**Układ listy i dane:** zakładki „Do zatwierdzenia”, „W obserwacji”, „Zakończone”; filtry domeny, priorytetu, automatyzacji, dat i statusu. Wiersz/karta: problem, cel, rekomendacja, priorytet, wiek, termin, poziom autonomii i status. „W obserwacji” grupuje m.in. oczekiwanie na dane; grupy UI są projekcją, nie nowymi stanami workflow.

**Detail:** breadcrumbs i prosty nagłówek; proponowana czynność oraz dokładny zasób; widoczna ważność i ryzyko; panel „Dlaczego”; dowody i źródła; link „Szczegółowa analiza”; reguły i zgoda; oś wykonania i weryfikacji. Rozwijane dane techniczne mają request/action ID dla diagnozy. Kwoty wpływu są oznaczone jako estymacje, o ile faktycznie istnieje metoda ich wyliczenia.

**Akcje:** zatwierdź konkretną rewizję, odrzuć z opcjonalnym powodem, poproś o ponowną analizę przy wygaśnięciu. Przed zgodą pokazujemy zakres i konsekwencje. Backend ponawia kontrolę polityki; UI nie dopisuje `approved=true`. L2 pokazuje stan wykonania, nie wymusza sztucznej zgody. Brak przycisku ponawiającego mutację przy UNKNOWN.

**Nawigacja:** Summary → Explanation → Evidence/Analytics, z przekazaniem `sourceProposalId`, produktu, kampanii i okresu. Historyczny snapshot ma etykietę „Dane użyte do decyzji”; aktualna analiza ma własny `asOf`. Powrót zachowuje rewizję i filtry skrzynki.

**Stany:** loading — lista i sekcje detail ładowane oddzielnie; empty — zero decyzji w filtrze lub wszystko obsłużone; error — brak detalu, błąd dowodów lub konflikt wersji z widocznym odświeżeniem; success — „Zgoda zapisana”, później osobne „Akcja potwierdzona”; stale/expired — ostrzeżenie i blokada zgody; pending — disabled CTA oraz widoczna oś operacji.

**Backend i API:** Intelligence udostępnia prezentację, Automation obsługuje komendy i powiązanie z Temporal.

| Endpoint | Wejście → wynik |
|---|---|
| `GET B/decisions` | Filtry, cursor → streszczenia propozycji i liczniki grup |
| `GET B/decisions/{proposalId}` | Opcjonalna `revision` → `DecisionPresentation`, ETag |
| `GET B/decisions/{proposalId}/evidence` | `revision,cursor` → dowody, snapshot i definicje; kontrola scope |
| `POST B/decisions/{proposalId}/approvals` | `{revision}`, If-Match → 202, approval ID i operation ID |
| `POST B/decisions/{proposalId}/rejections` | `{revision,reason?}`, If-Match → utrwalone odrzucenie i nowy status |
| `POST B/decisions/{proposalId}/reanalysis` | `{revision}`, If-Match → 202; nowa analiza/rewizja bez nadpisania starej |

Komendy stosują wspólną idempotencję. Wygaśnięcie, brak uprawnień i konflikt zwracają kody z sekcji 3. Anulowanie już rozpoczętej akcji nie jest obiecywane przez interfejs.

## 6. Customer Support + Conversation Detail

**Cel i scenariusze:** zobaczyć historię pracy AI, znaleźć sprawy wymagające człowieka, zrozumieć eskalację, przejąć rozmowę, poprawić szkic i odpowiedzieć. Proces, model domenowy i polityki mają źródło w [Customer Support Agent](02-AI-AGENT-ARCHITECTURE.md#14-customer-support-agent-d-p); UI nie implementuje drugiego zestawu reguł.

**Układ listy:** liczniki wszystkich konwersacji, spraw obsłużonych przez AI i wymagających interwencji; filtry statusu, przypisania, priorytetu, skrzynki i ostatniej aktywności. Wyszukiwanie wiadomości/e-maili ma uprawnienia Support i nie publikuje danych klienta w URL. Wiersz: klient lub bezpieczny identyfikator, temat, podgląd ostatniej wiadomości, status, assignee, czas oczekiwania i znacznik eskalacji. Czas oczekiwania liczony od ostatniej wiadomości klienta wymagającej odpowiedzi; nie od dowolnej aktywności systemowej.

**Układ Conversation Detail:** na desktopie trzy obszary — lista, historia i kontekst. Góra historii pokazuje status, właściciela oraz „Przejmij rozmowę”. Wiadomości mają autora, czas, źródło AI/człowiek/system i status wysyłki. Prawy panel: podsumowanie, powód eskalacji, zweryfikowany klient i zamówienia, źródła faktów, proponowana odpowiedź. Dół: composer z treścią szkicu, historią rewizji, informacją o zapisie i przyciskiem wysyłki. HTML wiadomości jest sanitizowany, zewnętrzne obrazki domyślnie nie są ładowane automatycznie; nie renderujemy aktywnego kodu.

**Prezentowane dane:** `Conversation.status`, priorytet, assignee, `ownershipVersion`, wersja wątku, powiązane zamówienia i stopień weryfikacji relacji, Message timeline, draft revision, niepewności i reason codes eskalacji. Statusy widoczne dla użytkownika: „Nowa”, „Obsługuje AI”, „Wymaga człowieka”, „Przejęta”, „Rozwiązana”. Statusy wiadomości pozostają osobne: szkic, w kolejce, wysyłanie, przyjęta przez dostawcę, dostarczona — jeśli mamy potwierdzenie — błąd lub nieustalony wynik. Nie nazywamy ACCEPTED „przeczytana” ani „doręczona”.

**Akcje:** przejmij/przypisz sprawę, edytuj szkic, przygotuj szkic AI na żądanie, wyślij dokładną rewizję, oznacz jako rozwiązaną, jawnie oddaj AI. Przejmowanie i wysyłka mają odrębne potwierdzenia. Composer blokuje send bez własności, uprawnień lub przy nieaktualnym wątku. Nowa wiadomość klienta nie nadpisuje tekstu operatora: pokazuje banner, unieważnia automatyczne wysłanie starego szkicu i wymaga przeglądu. Przy takeover w trakcie dispatch widzimy „Przejmujesz rozmowę; wcześniejsza wiadomość jest już w wysyłce” i jej stan. UI nie obiecuje cofnięcia wysłanego maila.

**Nawigacja:** zamówienie otwiera ograniczony drawer z faktami Commerce i ewentualnym linkiem do istniejącego systemu; nowy ekran zarządzania zamówieniami nie jest wymagany. Eskalacja może linkować powiązaną decyzję biznesową, lecz jej korespondencja zostaje w Support. Z Activity wracamy do konkretnej wiadomości; z Dashboardu otwieramy właściwą kolejkę. Dane klienta nie są dostępne dla analityka bez uprawnień Support.

**Stany:** loading — szkielet listy i historii bez przeskoku scrolla; empty — brak wiadomości, filtr bez wyników albo niepodłączona skrzynka z CTA; error — błąd synchronizacji/wysyłki przy konkretnej wiadomości, możliwość zachowania szkicu; success — potwierdzone przejęcie/zapis szkicu i odrębne przyjęcie wysyłki; UNKNOWN — informacja „Sprawdzamy, czy wiadomość została wysłana”, bez automatycznego resend. Stale pokazuje ostatnią synchronizację; konflikt właściciela blokuje wysyłkę i odświeża kontekst. Przy opuszczaniu widoku z niezapisanym szkicem wymagany jest czytelny wybór zachowania zmian.

**Backend i API:** Customer Support read models zwracają dane z odpowiednią minimalizacją; wysyłka i generowanie szkicu są operacjami asynchronicznymi.

| Endpoint | Wejście → wynik |
|---|---|
| `GET B/support/conversations` | Filtry, cursor → list items, liczniki według tego samego filtra, meta |
| `POST B/support/conversation-search` | `{query,filters,cursor}` → uprawnione wyniki; bez logowania treści zapytania jako zwykłego URL |
| `GET B/support/conversations/{id}` | → status, kontekst, eskalacja, draft summary, ownership/version, allowedActions, ETag |
| `GET B/support/conversations/{id}/messages` | `beforeCursor/afterCursor,limit` → Message DTOs z author/source/send state; chronologiczny timeline |
| `POST B/support/conversations/{id}/takeover` | `{assigneeId?}`, If-Match → nowe ownershipVersion i status; trwałe przejęcie |
| `POST B/support/conversations/{id}/release` | `{reason?}`, If-Match → oddanie AI, nowa wersja i ocena dopuszczalności |
| `POST B/support/conversations/{id}/draft-generation` | `{triggerMessageId}`, If-Match → 202 + operation ID; wynik jako nowy draft |
| `PATCH B/support/drafts/{draftId}` | `{bodyText}`, If-Match draftu → nowa draftRevision, contentHash i ETag |
| `POST B/support/conversations/{id}/send` | `{draftId,draftRevision,triggerMessageId,ownershipVersion}`, If-Match wątku → 202 + message/operation ID |
| `POST B/support/conversations/{id}/resolve` | `{resolutionCode}`, If-Match → nowy stan i zapis audytu |

Wszystkie komendy mają idempotencję z sekcji 3. `send` rozwiązuje adresata po stronie backendu i sprawdza bieżący stan. UI nie wysyła dowolnego `to`, `cc` ani danych zamówienia jako upoważnienia. Message DTO zawiera ID, direction, authorType, bezpieczną treść, daty, provider reference, status i failure reason, nie token dostawcy. **O-11–O-13:** provider, reguły tożsamości, retencja i obsługa załączników pozostają otwarte; UI pokazuje załącznik jako nieprzeanalizowany, dopóki system nie ma obsługiwanej ścieżki.

## 7. Analytics: Sales, Profitability, Ads, Products

**Cel i scenariusze:** samodzielna analiza wyników i weryfikacja rekomendacji. Użytkownik porównuje okresy, bada kampanię lub SKU, sprawdza źródła i brakujące koszty. Pełne cztery zakładki są w MVP; nie wymagają trybu eksperta.

**Wspólny układ:** pasek dat, porównanie, strefa i aktywne filtry; karty KPI; wykres trendu; tabela drill-down; rozwijane definicje i źródła. Filtry mają reset oraz widoczne chips. Kliknięcie serii/wiersza zawęża analizę, breadcrumbs umożliwiają wycofanie. Sortowanie, paginacja i agregacja odbywają się w backendzie. Dla dużych zestawów nie pobieramy wszystkich zamówień do przeglądarki.

| Zakładka | Dane i układ szczegółów | Akcje i drill-down |
|---|---|---|
| Sales / Sprzedaż | Sprzedaż netto, zamówienia kwalifikujące się, AOV, refundacje/zwroty, trendy i porównania | Dzień → zamówienia w ograniczonym drill-down; produkt → Products; definicja AOV i baza okresu |
| Profitability / Rentowność | Sprzedaż netto, COGS, fulfillment, opłaty, reklamy, znane koszty zmienne; waterfall kosztów, marża i pokrycie danych kosztowych | Rozwinięcie składnika kosztu, alokacji i braków; link do uzupełnienia kosztów w Settings dla uprawnionych |
| Ads / Reklamy | Wydatek Meta, platformowe konwersje, ROAS/CPA, tabela kampanii i trend, model/okno atrybucji i opóźnienie danych | Campaign → adset → ad; porównanie i link do powiązanego SKU tylko przy znanym mapowaniu |
| Products / Produkty | Sprzedaż i marża SKU, zapas, tempo sprzedaży, pokrycie, dostawy i niepewność prognozy | Wariant → wykres sprzedaży/zapasu → powiązane reklamy/decyzje i historyczne działania |

MVP obejmuje analizy i stałe zestawy porównań, nie dowolny język raportowy. „Zysk” zastępujemy precyzyjną nazwą marży kontrybucyjnej po znanych kosztach; definicji nie powielamy tutaj — obowiązuje [model finansowy](01-PRODUCT-VISION-AND-ARCHITECTURE.md#6-model-biznesowy-danych-u-p). AOV/ROAS/CPA mają jawne definicje i mianowniki; zero konwersji nie generuje nieskończonej wartości CPA. Różne waluty pokazujemy oddzielnie, dopóki nie wdrożono jawnego przeliczenia z kursem i datą. Metryki Meta nie są nazywane potwierdzoną atrybucją do unikalnych zamówień sklepu.

**Dane i jakość:** każda karta i tabela udostępnia MetricDTO, pokrycie kosztów i datę synchronizacji. Estimated ma badge z przyczyną; Unavailable pokazuje „Brak danych” i brakujące składniki. Nie łączymy braku wartości linią sugerującą zero. Wariant w Decision Detail może być historyczny; „Otwórz bieżącą analizę” jawnie przełącza snapshot na aktualny stan.

**Akcje i nawigacja:** zmień okres, porównanie, granularność i filtr; otwórz źródło/definicję; przejdź do powiązanej decyzji, Activity albo Integrations. Brak bezpośredniego pauzowania reklam z tabeli omijającego workflow. Eksport analityki pozostaje kwestią O-14; nie jest wymagany do podstawowego drill-down MVP.

**Stany:** loading — karty i wykres z skeletonami, aktywny kontekst dat; empty — brak danych dla filtra, bez fikcyjnych zer; error — błąd zapytania lokalnie dla zakładki/tabeli; success — ten sam query/data version dla powiązanych liczb; stale — watermarky źródeł i ostrzeżenie o opóźnieniu; partial — dostępna sprzedaż przy brakującej rentowności, z czytelną przyczyną.

**Backend i API:** Reporting korzysta z wersjonowanych definicji i agregatów Commerce. Zapytania nie uruchamiają reasoning LLM. Wszystkie endpointy przyjmują `from,to,timezone,compare,granularity` oraz allowlistowane filtry; wspólny `queryId/dataVersion` pozwala łączyć widoki tego samego wyniku.

| Endpoint | Wynik i dodatkowy kontekst |
|---|---|
| `GET B/analytics/sales` | Metrics, series, breakdowns, definicje i meta |
| `GET B/analytics/profitability` | Metrics, costBreakdown, costCoverage, allocationMethods, missingComponents |
| `GET B/analytics/ads` | Metrics i series; `campaignId/adsetId/adId`, attributionSettings |
| `GET B/analytics/products` | Metrics, series, paginated SKU rows; `productVariantId`, forecastMethod i risk flags |
| `GET B/analytics/{tab}/rows` | `queryId`, whitelisted dimension, cursor, sort → drill-down tej samej wersji; po wygaśnięciu query jawne odświeżenie |
| `GET B/analytics/definitions/{key}` | Wersja definicji, jednostki, mianownik, metoda i źródła; bez duplikowania algorytmu w UI |

Wynik analizy ma czytelne statusy kompletności. **O-14:** docelowe wolumeny i limity opóźnień zdecydują o potrzebie asynchronicznych ciężkich zapytań; obecny projekt wymaga paginacji i limitów zakresu, nie obiecuje dowolnego czasu odpowiedzi.

## 8. Automations + konfiguracja autonomii

**Cel i scenariusze:** zrozumieć, co system robi w tle, sprawdzić gotowe procesy, skonfigurować dopuszczone działania i limity, wstrzymać proces. Nie wymagamy znajomości konfiguracji agentów.

**Układ i dane:** lista siedmiu procesów z [zakresu MVP](01-PRODUCT-VISION-AND-ARCHITECTURE.md#siedem-procesów-korzystających-ze-wspólnego-rdzenia), z nazwą użytkową („Obsługa wiadomości klientów” zamiast technicznej nazwy profilu), opisem, statusem, ostatnim/następnym uruchomieniem, zakresem L0/L1/L2 i zdrowiem zależności. Detail zawiera działanie procesu, dozwolone czynności, efektywną politykę, limity, wyłączenia, historię oraz rozwijane szczegóły.

**Akcje:** wstrzymaj/wznów proces, zmień dozwolony poziom per akcja i zakres, ustaw limity, sprawdź skutki zmiany. L2 wymaga pokazania konkretnych dopuszczonych czynności i jawnego potwierdzenia. L3 jest oznaczone jako poza MVP i nieaktywne. „Wstrzymaj automatyzację” zatrzymuje nowe działania tego procesu, nie usuwa integracji ani audytu i nie cofa wysłanej akcji. Dla Support odrębnie wstrzymujemy automatyczne odpowiedzi; odbiór wiadomości i praca człowieka pozostają dostępne, o ile globalne polityki na to pozwalają.

**Nawigacja:** ostatni run → Activity; wymagana zgoda → Decision Detail; eskalacje Support → jego kolejka; awaria zależności → Integrations; globalna polityka → Settings z powrotem do procesu. Formularz pokazuje, które wartości dziedziczy, a które nadpisuje w dozwolonym zakresie. Edytor globalnych reguł istnieje w Settings, bez drugiego niezależnego źródła konfiguracji.

**Stany:** loading — karty; empty — dostępne procesy jeszcze nieskonfigurowane, bez kreatora dowolnego workflow; error — zależność blokuje proces, czytelny link do naprawy; success — zapis polityki z wersją i zakresem; conflict — nowa wersja wymaga przeglądu; partial/stale — brak bieżącego health blokuje zwiększenie autonomii.

**Backend i API:** Automation zwraca rozdzielnie konfigurację żądaną i efektywną po zastosowaniu nadrzędnych polityk.

| Endpoint | Wejście → wynik |
|---|---|
| `GET B/automations` | → karty procesów, status, dependencies, effectiveAutonomy |
| `GET B/automations/{id}` | → dozwolone akcje, konfiguracja, wersja, effectivePolicy, historia referencyjna |
| `POST B/automations/{id}/policy-preview` | Proponowane ustawienia + baseVersion → różnica uprawnień i blokujące reguły; bez zapisu |
| `PATCH B/automations/{id}/configuration` | Zatwierdzony zakres + If-Match → nowa konfiguracja; serwer ponownie wylicza politykę |
| `POST B/automations/{id}/pause` i `/resume` | If-Match, reason → utrwalony stan i wpływ na aktywne operacje |

Preview nie jest upoważnieniem do przyszłej akcji; zapis i executor nadal egzekwują aktualny stan. Każda zmiana jest audytowana i dostępna wyłącznie uprawnionej roli.

## 9. Activity History

**Cel i scenariusze:** zobaczyć wykonaną pracę, znaleźć przyczynę błędu i zweryfikować skutek. Widok jest czytelną historią operacyjną z możliwością wejścia w audyt.

**Układ i dane:** timeline lub tabela z filtrem typu (wykrycie, rekomendacja, akcja, odpowiedź, eskalacja, retry, weryfikacja), procesu, wyniku, czasu i aktora. Wiersz: prosty opis, zasób, czas, AI/człowiek/system oraz wynik. Detail zawiera pre/post-state, powiązaną propozycję/draft, wersję polityki, oś prób i weryfikacji oraz identyfikatory korelacyjne. Odpowiedzi klientów w ogólnej historii są redagowane dla ról bez dostępu Support.

**Akcje i nawigacja:** filtrowanie, kopiowanie bezpiecznego ID diagnostycznego, przejście do decyzji, rozmowy, integracji lub kontekstowej analityki. Kontrolowane ponowienie jest widoczne tylko, jeśli backend zwróci je jako dozwoloną komendę właściwego zasobu. Nie ma uniwersalnego „cofnij” ani „ponów” dla każdego eventu.

**Stany:** loading — szkielet osi; empty — brak aktywności w okresie; error — błąd pobrania strony z zachowaniem filtrów; success — potwierdzony wynik i dalsze linki; UNKNOWN — wyróżniony status niepewności z właścicielem eskalacji; pending — proces trwa, nie jest zakończonym sukcesem.

**Backend i API:** Audit jest historią, nie interfejsem swobodnej mutacji zdarzeń.

| Endpoint | Wejście → wynik |
|---|---|
| `GET B/activities` | Filtry, cursor → ActivitySummary[], nextCursor, meta |
| `GET B/activities/{id}` | → timeline, actor, resourceRefs, before/after, outcome, retry history, policy/proposal refs, redacted audit |

Treści e-mail udostępnia właściwy endpoint Support z własną kontrolą dostępu. Opóźniona projekcja audytu jest oznaczona; nie usuwa potwierdzonego wyniku ActionExecution.

## 10. Integrations

**Cel i scenariusze:** podłączyć źródła istniejącego sklepu, sprawdzić uprawnienia i świeżość, odnowić dostęp, wykonać synchronizację i bezpiecznie odłączyć połączenie.

**Układ i dane:** karty WooCommerce, Meta Ads, skrzynki e-mail Support i opcjonalnego Slacka. Detail: osobno status autoryzacji, odbioru, wysyłki i synchronizacji; ostatni sukces, watermark, backlog, zakres uprawnień, konto/skrzynka, zależne procesy i czytelny błąd. E-mail do klientów i wewnętrzny kanał raportów są opisane odrębnie. Sekrety są maskowane i nigdy nie wracają z backendu.

**Akcje:** połącz, sprawdź połączenie, odnów zgodę, wybierz uprawnione konto/skrzynkę, synchronizuj, odłącz po pokazaniu zależności. Podłączenie dostępu do wysyłki nie aktywuje automatycznie odpowiedzi L2. Odłączenie blokuje nowe zależne mutacje i nie oznacza skasowania historii. Test wysyłki, jeśli potrzebny dla wybranego dostawcy, wymaga jawnego odbiorcy testowego i zgody; nie wysyła się do klientów w tle.

**Nawigacja:** błąd konkretnej automatyzacji → detail odpowiedniej integracji; brak kosztów → Settings; po podłączeniu link do postępu onboarding albo uruchomienia analizy. Otwierane zewnętrzne procesy autoryzacji mają powrót do tego samego kroku i informację o wyniku.

**Stany:** loading — health i zależności oddzielnie; empty — połączenie jeszcze nie istnieje; error — token, zakres lub synchronizacja z konkretnym krokiem naprawy; success — „Połączono” i osobno „Synchronizacja ukończona”; partial — odczyt działa, wysyłka nieaktywna; stale — ostatni sukces i zablokowane procesy. Sam HTTP sukces OAuth nie oznacza gotowych danych.

**Backend i API:** Integrations realizuje walidację kont, sekretów, uprawnień, callbacków i URL sklepu; frontend nie przechowuje tokenów w adresie ani pamięci trwałej aplikacji.

| Endpoint | Wejście → wynik |
|---|---|
| `GET B/integrations` / `GET B/integrations/{id}` | → capabilities, health, account label, freshness, sync jobs, dependencies, allowedActions |
| `POST B/integration-connections` | `{provider,returnPath,storeUrl?}` → connection ID i bezpieczny następny krok; returnPath z allowlist |
| `POST B/integration-connections/{id}/credentials` | Sekrety tylko dla connectora wymagającego formularza → status bez zwracania wartości |
| `POST B/integrations/{id}/checks` | → 202 + operation ID, odczytowy test dostępu |
| `POST B/integrations/{id}/sync-jobs` | `{scope}`, bez nieograniczonego backfill → 202 + job ID |
| `POST B/integrations/{id}/reauthorization` | → ścieżka odnowienia, bez automatycznego rozszerzenia autonomii |
| `POST B/integrations/{id}/disconnect` | If-Match + reason → stan odłączony i lista zatrzymanych zależności |

Callback po stronie backendu wiąże żądanie autoryzacji z właściwym użytkownikiem/sklepem; testuje zakres przed nadaniem statusu ready. **O-11:** wybór dostawcy e-mail i sposobu łączenia pozostaje otwarty, dlatego nie narzucamy konkretnego OAuth/IMAP/SMTP flow jako zatwierdzonej implementacji.

## 11. Settings & Policies

**Cel i scenariusze:** utrzymać dane sklepu, role, globalne granice działań, finanse, zasady odpowiedzi i kanały wewnętrzne. Uprawniony administrator może zatrzymać mutacje i przejrzeć skutki zmian.

**Układ i dane:** zakładki „Sklep”, „Polityki”, „Zespół”, „Powiadomienia”, „Dane finansowe”, „Zasady obsługi klientów”. Widoczny globalny stan zatrzymania, wersje polityk i dziedziczone wartości. Konfiguracja per automatyzacja linkuje do Automations, wykorzystując ten sam Policy Service. Dane: strefa/waluta sklepu, role i scope, limity, odbiorcy raportów, brakujące koszty, zatwierdzone źródła odpowiedzi oraz kategorie L2 Support.

**Akcje:** edytuj, obejrzyj diff i wpływ, zapisz wersję; przypisz uprawnienia; uzupełnij koszty; zatrzymaj mutacje; osobno i świadomie przywróć możliwość ich wykonywania. Globalny kill switch dotyczy zakresu zwróconego przez backend, w tym wysyłek do klientów objętych blokadą; nie tworzymy ukrytego obejścia dla przycisku człowieka. UI pokazuje, że już wysłanej akcji nie cofnie. Zatrzymanie nie kasuje wiadomości ani nie wyłącza dozwolonych odczytów.

**Nawigacja:** brak COGS w Analytics → odpowiedni formularz finansowy z kontekstem SKU; niedozwolona odpowiedź → SupportPolicy; efektywna reguła → używające jej automatyzacje i audyt zmian. Odczyt polityki nie daje prawa do jej edycji.

**Stany:** loading — formularz bez aktywnych save; empty — brak kosztów/odbiorców z wyjaśnieniem skutku; error — walidacja przy polach i blokada niepoprawnego zapisu; success — nowa wersja i zakres obowiązywania; conflict — diff względem nowszej wersji, bez cichego nadpisania; partial — niektóre źródła danych nieuzupełnione, pozostałe ustawienia nadal dostępne.

**Backend i API:** Tenants/Identity przechowują konfigurację i scope, Policy Service wylicza efektywną politykę, a Audit rejestruje zmianę. Role robocze **[P]**: właściciel/admin polityk, operator decyzji, operator Support, analityk odczytu; konkretną macierz ustalamy w O-13. Są to role dostępu, nie tryby doświadczenia użytkownika.

| Endpoint | Wejście → wynik |
|---|---|
| `GET/PATCH B/settings/store` | Nazwa, timezone, waluta i uzgodnione ustawienia; If-Match → wersja |
| `GET B/policies` / `GET B/policies/{id}` | → scope, wersja, reguły i źródła dziedziczenia |
| `POST B/policies/{id}/preview` | Proponowana konfiguracja/baseVersion → diff i wpływ, bez zmiany stanu |
| `POST B/policies/{id}/versions` | Konfiguracja + If-Match → nowa obowiązująca wersja i audyt |
| `POST B/mutation-stop` | `{enabled,reason}`, If-Match aktualnego stop state → potwierdzony zakres i stan; wyłączenie wymaga odrębnej zgody uprawnionego użytkownika |
| `GET/PATCH B/settings/notifications` | Kanały wewnętrzne, harmonogram, odbiorcy; wersjonowanie i test jako osobna jawna akcja |
| `GET/PATCH B/settings/financial-inputs` | Koszty z okresem obowiązywania, źródłem i SKU; wynik walidacji i przeliczeń |
| `GET B/team` / `PATCH B/team/{memberId}/permissions` | Dostępne role/scope; zmiana tylko w zakresie uprawnień administratora, If-Match |

SupportPolicy jest pozycją w tym samym katalogu polityk, nie nową implementacją Policy Engine. Zmiana waluty lub definicji kosztów wymaga informacji o sposobie przeliczenia historii; nie nadpisujemy historycznych dowodów decyzji.

## 12. Onboarding istniejącego sklepu [D, P]

**Cel:** podłączyć istniejący sklep i włączyć monitoring z widoczną gotowością każdej domeny. Nie ma etapu „Utwórz sklep”, generowania witryny, katalogu ani storefrontu.

| Krok | Układ, dane i akcje | Warunek ukończenia / przejście |
|---|---|---|
| 1. Podłącz WooCommerce | Formularz istniejącego adresu i bezpieczne połączenie; nazwa sklepu, strefa, konto i test odczytu | Zweryfikowane połączenie i rozpoczęta synchronizacja; błędy hostingu/dostępu pokazane w miejscu wystąpienia |
| 2. Podłącz narzędzia | Karty Meta, e-mail Support i opcjonalnego Slacka; wybór uprawnionego konta/skrzynki i zakresów | Każda domena otrzymuje własny stan gotowości. Można wrócić do konfiguracji; brak integracji blokuje zależny proces, nie cały Dashboard |
| 3. Uzupełnij dane biznesowe | Koszty, waluta/strefa, zasady zapasu, polityki sklepu i mapowania; informacja o brakach | Braki mają listę skutków. Analiza sprzedaży działa przed uzupełnieniem wszystkich kosztów; L2 Inventory wymaga potwierdzonej semantyki i mapowań |
| 4. Uruchom monitoring | Podsumowanie źródeł, zakres L0/L1 i właściciele eskalacji; „Uruchom monitoring” | Bez samoczynnego L2 reklam i odpowiedzi klientom. Przejście do Dashboardu z checklistą gotowości |

Po pierwszej synchronizacji operator może osobno skonfigurować SupportPolicy, przetestować szkice i jawnie uruchomić dozwolone odpowiedzi L2. Podłączenie skrzynki odczytu nie oznacza zgody na wysyłkę. Pominięcie e-maila w trakcie onboardingu pozostawia widok Support dostępny z instrukcją podłączenia, a nie usuwa modułu z MVP. Brak konta Meta analogicznie pozostawia działającą analitykę sprzedaży i widoczne nieaktywne procesy reklamowe.

**Nawigacja:** stepper z zapisem postępu, Wstecz/Dalej, „Dokończ później” i powrót do konkretnej integracji. Sukces oznacza rzeczywistą gotowość monitoringu danego zakresu; trwały import ma postęp i osobny rezultat. Zakończenie wizardu nie ukrywa niepełnej synchronizacji.

**Stany:** loading — test połączenia albo import z etapem, bez fałszywego procentu; empty — źródło jeszcze niepodłączone; error — czytelna przyczyna i bezpieczne ponowienie; success — potwierdzone połączenie/dane lub monitoring, odrębnie; partial/stale — checklista brakujących źródeł i ograniczonych funkcji. Anulowanie autoryzacji wraca do tego samego kroku. Odświeżenie strony nie resetuje ukończonych etapów ani nie wysyła ponownie testowej wiadomości.

**Backend i API:** połączenia korzystają z endpointów Integrations, a ustawienia z Settings; onboarding nie powiela ich logiki.

| Endpoint | Wejście → wynik |
|---|---|
| `GET B/onboarding` | → etapy, readiness per domena, import status, warnings, wersja i nextStep |
| `PATCH B/onboarding` | `{currentStep,completedAcknowledgements}`, If-Match → zapis postępu; backend sam ocenia gotowość |
| `POST B/onboarding/monitoring-start` | `{requestedScopes}`, If-Match → 202 + monitoring operation, efektywny zakres L0/L1 i blokady |

Zarejestrowanie istniejącego adresu sklepu w platformie jest technicznym powiązaniem konta, nie utworzeniem sklepu e-commerce. Backend waliduje uprawnienia, URL i zakres connectora, a UI nie przyjmuje deklaracji „gotowe” jako dowodu dostępu do danych.

## 13. Przepływy użytkownika i mapowanie do domen [P]

| Przepływ | Kolejne widoki | Zachowywany kontekst i rezultat |
|---|---|---|
| Ryzyko zapasu L1 | Dashboard → Decision Detail → Analytics Products → Detail → zgoda → Activity | Wariant, snapshot i okres; zgoda na konkretną rewizję; techniczny wynik pauzy. Logika: [Inventory Guardian](03-INVENTORY-GUARDIAN-E2E.md#14-powiązanie-procesu-z-ui-p) |
| Wykonana akcja L2 | Dashboard / Activity → Decision Detail → Ads | Akcja, polityka, cel i czas; brak sztucznego pytania o zgodę po wykonaniu |
| Eskalacja e-mail | Dashboard → Support z filtrem → Conversation Detail → takeover → edycja → send | Conversation, trigger i ownership version; status wysyłki pozostaje widoczny |
| Analiza marży | Dashboard → Profitability → brakujący COGS → Settings → Profitability | Daty i SKU; przeliczenie opisane jako nowa wersja, bez zmiany historycznej decyzji |
| Naprawa integracji | Ostrzeżenie → Integration Detail → reauthorization/sync → poprzedni widok | Konto, błąd i źródło; akcja biznesowa wymaga ponownej oceny |
| Zwiększenie autonomii | Automations → preview polityki → potwierdzenie → Activity | Konkretny zakres akcji i nowa wersja; ograniczenia nadrzędne nadal obowiązują |

UI używa wspólnego `AnalysisContext` i identyfikatorów korelacyjnych. Decision Inbox obsługuje decyzje biznesowe, Customer Support rozmowy, Analytics read models, Activity audyt, a Automations/Settings ten sam moduł polityk. Komponenty nie komunikują się bezpośrednio z dostawcami LLM, Meta ani poczty. Żaden ekran nie nadaje modelowi nowych tools.

## 14. Dostępność i zachowanie interakcji [P]

Wszystkie funkcje są dostępne z klawiatury, z widocznym fokusem, semantycznymi etykietami i logiczną kolejnością. Drawer/dialog zarządza fokusem i zwraca go do źródła; Escape zamyka nieszkodliwy podgląd, lecz nie odrzuca niezapisanego szkicu bez ostrzeżenia. Przy zmianie route fokus przechodzi do nagłówka, przy odświeżeniu danych nie jest przenoszony.

Wykres ma opis tekstowy i tabelę danych; tooltip jest dostępny z klawiatury. Kolor nie jest jedynym nośnikiem statusu, wzrost nie zawsze jest korzystny (np. CPA). Cel kontrastu: minimum 4,5:1 dla zwykłego tekstu i 3:1 dla dużego tekstu oraz istotnych elementów interaktywnych. Wymagane są pomiary wybranej palety; nie deklarujemy certyfikacji na podstawie specyfikacji. Powiększenie 200% nie usuwa dostępu do akcji. Animacje są ograniczone i respektują preferencję redukcji ruchu.

Formularze mają etykiety, błędy przy polach i podsumowanie. Zmiany wysyłki są ogłaszane przez region statusu bez czytania całej historii od nowa. Ważny błąd pozostaje w widoku także po zniknięciu toastu. Duże tabele mają nagłówki i logiczne sortowanie, a wirtualizacja zachowuje dostępność. Touch targets mają docelowo co najmniej 44 × 44 px; gęste dane nie wymuszają równie małych przycisków.

Daty pokazujemy z nazwą strefy w kontekście, względny czas ma dokładny timestamp w dostępnym podglądzie. Waluty i procenty mają jednoznaczne jednostki. Język komunikatów opisuje sytuację i następny krok, bez technicznych wyjątków lub ukrywania niepewności.

## 15. Kryteria akceptacyjne UI [P]

| ID | Scenariusz odbioru |
|---|---|
| UX-01 | Osiem widoków i onboarding są dostępne zgodnie z uprawnieniami; brak kreatora sklepu i trybu ekspert/początkujący. |
| UX-02 | Przejście Dashboard → decyzja → analityka → powrót zachowuje sklep, produkt, daty, filtry i rewizję. |
| UX-03 | Liczby Summary/Explanation/Evidence dla danego snapshotu są zgodne; bieżące dane nie podmieniają historycznych dowodów. |
| UX-04 | Cztery zakładki Analytics mają porównania, drill-down i definicje; brak kosztu daje Unavailable/Estimated z przyczyną, nie fałszywe zero. |
| UX-05 | Zgoda na wygasłą lub zmienioną rewizję jest odrzucona; podwójne kliknięcie nie duplikuje akcji. |
| UX-06 | UNKNOWN i przyjęcie komendy nie są prezentowane jako potwierdzony skutek; nie ma ślepego resend/retry. |
| UX-07 | Eskalacja Support pokazuje podsumowanie, powód i szkic; takeover wyłącza możliwość nowej automatycznej odpowiedzi według stanu backendu. |
| UX-08 | Nowy mail/zmiana właściciela w czasie edycji nie gubi lokalnego tekstu i blokuje wysyłkę nieaktualnego szkicu. |
| UX-09 | ACCEPTED, DELIVERED, BOUNCED i UNKNOWN są odróżnione i mają źródła; autor AI/człowiek jest widoczny. |
| UX-10 | Każdy z ośmiu widoków i onboarding przechodzi loading, empty, error, success, stale i partial z użytecznym kolejnym krokiem. |
| UX-11 | Odnowienie integracji nie aktywuje samo L2; kill switch pokazuje skuteczny zakres i nie obiecuje cofnięcia już wysłanej akcji. |
| UX-12 | Analityk bez uprawnień Support nie widzi treści maili w Dashboard, Activity, wyszukiwarce ani bezpośrednim linku. |
| UX-13 | Kontekst sklepu i cache nie przeciekają przy przełączeniu konta lub roli; backend sprawdza każdą komendę. |
| UX-14 | Krytyczne przepływy są wykonalne klawiaturą, na telefonie i przy powiększeniu; wykres ma alternatywę tabelaryczną. |
| UX-15 | Onboarding można przerwać i wznowić; zakończenie bez e-mail/Meta pokazuje ograniczenia, a monitoring nadal startuje tylko dla gotowego zakresu. |

Weryfikacja obejmuje testy komponentów i kontraktów API, testy przeglądarkowe powyższych ścieżek, kontrolę dostępności i ręczne zadania z użytkownikami o różnym doświadczeniu. Makiety i dane demonstracyjne muszą być wyraźnie oznaczone. Te kryteria są planem odbioru, nie raportem przeprowadzonych testów aplikacji.

## 16. Otwarte kwestie projektowe [O]

| ID | Do rozstrzygnięcia | Wpływ |
|---|---|---|
| O-11 | Dostawca poczty, synchronizacja Sent, statusy i deduplikacja wysyłki | Konkretne kroki połączenia i dostępne potwierdzenia w Conversation Detail |
| O-12 | Reguły tożsamości, kategorie L2 i zatwierdzona wiedza sklepu | Zakres odpowiedzi i prezentacja brakujących danych |
| O-13 | Macierz ról, SLA eskalacji, retencja i załączniki | Kolejki, uprawnienia, priorytety i widoczność historii |
| O-14 | Wolumeny danych, czas odpowiedzi i eksporty analityczne | Paginacja, ciężkie zapytania i ewentualny eksport asynchroniczny |
| O-15 | Polling czy SSE oraz dopuszczalne opóźnienie aktualizacji | Powiadomienia o nowych mailach, zmianie własności i stanie operacji |
| O-16 | Finalny branding, walidacja palety i testy użyteczności | Wykończenie design systemu; bez zmiany zakresu ekranów |
| O-17 | Domyślna długość historii importowanej przy onboardingu | Czas gotowości, analityka porównań i backlog poczty |

D-04–D-07 nie są pytaniami otwartymi. Dobór providera, parametrów wizualnych i szczegółowych endpointów wymaga zatwierdzenia implementacyjnego, bez ponownego rozstrzygania obecności analityki i Support w MVP.

## Zakres pierwszej wersji dokumentu

Nowy dokument gromadzi jedną specyfikację nawigacji, design systemu, ośmiu widoków, onboardingu, stanów UI i proponowanych kontraktów API. Definicje biznesowe, polityki wykonania i logika Inventory Guardian pozostają w dokumentach 01–03, do których prowadzą odsyłacze.

## 17. Uzupełnienie Business Knowledge & Decision Intelligence [D, P]

Zachowujemy osiem widoków i progressive disclosure. Wiedza biznesowa nie wymaga dziewiątego głównego ekranu ani formularza pełnego arbitralnych progów KPI. Poniższa delta rozszerza istniejące widoki i kontrakty, bez powtarzania algorytmów z [05](05-DECISION-INTELLIGENCE-ARCHITECTURE.md).

| Widok | Uzupełnienie prezentacji i działań |
|---|---|
| Dashboard | Wskaż aktualny cel sklepu i odróżnij „proponujemy działanie”, „obserwujemy”, „bez potrzeby zmian” i „brakuje danych”. NO_ACTION nie musi zajmować kolejki pilnych decyzji. |
| Decision Inbox/Detail | Badge rodzaju decyzji, krótkie „dlaczego” i wpływ celu sklepu. MONITOR pokazuje co/po co/do kiedy obserwujemy oraz kiedy nastąpi kolejna ocena. NO_ACTION pokazuje powód i zakończenie oceny. INSUFFICIENT_DATA wskazuje konkretny brak i następny krok; nie sugeruje świadomej decyzji o pozostawieniu kampanii. |
| Analytics i Evidence | Przy każdej istotnej liczbie źródło, okres, observedAt, data maturity, jakość, estymacje/alokacje i link do definicji. Z panelu dowodu można sprawdzić wersję definicji, pochodzenie i ograniczenia wiarygodności. |
| Activity | Oddzielny rezultat techniczny i karta obserwacji biznesowej z baseline, follow-up, oknem, jakością i zakłóceniami. Etykiety OBSERVED_ASSOCIATION/INCONCLUSIVE przedstawiaj jako „zaobserwowana zmiana”/„brak rozstrzygnięcia”, bez etykiety „AI zarobiło” na podstawie samego before/after. |
| Settings & Policies | Podsekcja „Cele i strategia sklepu”: wybór rentowność/wzrost/równowaga, priorytety i tolerancja ryzyka. Autopozyskane koszty i budżety pokazują źródło i konflikty; edycja tworzy wersję profilu, nie automatycznie uprawnienia L2. |
| Onboarding | W kroku danych biznesowych zapytaj o podstawowy cel i pokaż pozyskane z integracji koszty/ograniczenia. Poproś wyłącznie o brakujące informacje istotne dla dostępnych procesów; nie wymuszaj nieznanej marży jako zera. |

**Wyjaśnianie:** poziom 1 „Nie zmieniamy teraz budżetu, bo konwersje jeszcze spływają”; poziom 2 opisuje dowody, strategię i ryzyko; poziom 3 pokazuje właściwe KPI, okna atrybucji, próbę oraz źródła. To przykład komunikatu, nie reguła zawsze zalecająca czekanie. Opóźniony feed, mała próba i brak danych mają różne przyczyny, terminy i ścieżki naprawy. Model nie tworzy nowej wartości KPI do użycia w tekście.

**DTO/API — delta [P]:** `DecisionPresentation` dodaje `decisionKind`, `knowledgeReleaseRef`, `storeProfileVersion`, `contextManifestRef`, `monitorPlan`, `insufficientDataReasons`, `outcomeRefs`. `decisionKind` jest normalizowaną projekcją backendu według reguł zgodności w dokumencie 05, nie zgadywaniem frontendu z tekstu. MetricDTO rozszerza się o `provenanceRef`, `maturity`, `availableAt`, `reliability`, `limitations`; istniejące pola quality/freshness zachowują znaczenie. Profile, metryki i źródła mają osobne poziomy wiarygodności — nie zastępujemy ich jednym „confidence AI”.

| Endpoint (`B` jak w sekcji 3) | Wejście → wynik |
|---|---|
| `GET B/business-profile` | → wersjonowany profil, zatwierdzone/proponowane wartości, źródła, braki, konflikty, ETag i allowedActions |
| `POST B/business-profile/preview` | Proponowane zmiany + baseVersion → wpływ na analizy i zależne decyzje; bez zapisu |
| `POST B/business-profile/versions` | Zmiany + If-Match + Idempotency-Key → nowa zatwierdzona wersja; brak nadania uprawnień do akcji |
| `GET B/decisions/{proposalId}/outcomes` | `revision,cursor` → plany i obserwacje, baseline/follow-up, maturity, confounders, attributionStrength |
| `GET B/evidence/{evidenceId}/provenance` | → źródło i lineage dostępne roli/tenantowi, wersja definicji i ograniczenia; bez sekretów/niepotrzebnego PII |

Istniejące formularze finansowe aktualizują swój system źródłowy; Store Profile referencjonuje zatwierdzone, wersjonowane dane kosztowe i polityki. Nie tworzymy drugiego niezależnego edytora tych samych kwot. Zmiana strategii wymaga uprawnienia do profilu i audytu; nie wolno agentowi samemu wybrać nowego celu po korzystnym Outcome.

**Stany dodatkowe:** przy loading zachowaj dotychczasowy zatwierdzony profil i wskaż odświeżenie; empty — brak skonfigurowanego celu lub brak zakończonego okna obserwacji, bez fikcyjnego defaultu zaakceptowanego przez klienta; error/conflict — pokaż konkurencyjne źródła i nową wersję; success — nowa wersja profilu lub zakończona obserwacja z jej jakością, bez implikacji skutku przyczynowego. Stare okno obserwacji może być SUPERSEDED/CENSORED, co nie usuwa starego rekordu.

**Dodatkowe kryteria odbioru [P]:** użytkownik rozróżnia MONITOR od NO_ACTION i braku danych; cel GROWTH nie odblokowuje przycisku zmiany budżetu; konflikt marży nie znika po odświeżeniu strony; spóźniony wynik konwersji tworzy rewizję obserwacji, nie nadpisuje historycznych dowodów; link źródła respektuje uprawnienia. Nowe pytania o profil, jakość źródeł i ocenę wyników zebrano jako O-18–O-23 w dokumencie 05.

## Zmiany w wersji 1.1

Dodano konfigurację Store Business Profile, rozróżnienie rodzajów decyzji, provenance/maturity metryk i prezentację Outcome. Rozszerzono wymagania istniejących ekranów oraz DTO/API, zachowując osiem widoków i zasadę „Simple by default. Powerful by design.”.
