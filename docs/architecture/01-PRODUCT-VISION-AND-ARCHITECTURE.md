# Autonomous E-commerce Operator — koncepcja produktu i architektura MVP

Wersja: 1.2 · Data: 14 listopada 2026 · Status: projekt do przeglądu

Dokument nadrzędny dla zakresu produktu. Szczegóły techniczne zawiera [architektura AI](02-AI-AGENT-ARCHITECTURE.md), referencyjną realizację — [Inventory Guardian end-to-end](03-INVENTORY-GUARDIAN-E2E.md), a widoki i kontrakty frontend–backend — [specyfikacja UI/UX](04-UI-UX-SPECIFICATION.md). Zalecana kolejność lektury: produkt → AI → proces → UI.

## 1. Podstawa i sposób czytania

Podstawą jest rozmowa „Brainstorming agenta ecommerce”, identyfikator `6af84827-e08c-83ed-a972-76644325952c`, oraz bieżące zamówienie trzech dokumentów. Odczytano cztery dostępne tury, w tym wcześniejszy brainstorming i wybór DTC. Narzędzie zwróciło dwie dłuższe odpowiedzi asystenta ucięte do 20 000 znaków każda; ich niewidocznych zakończeń nie uznajemy za znane ustalenia. Niedostępne wartości dawnych suwaków i przełączników nie stanowią konfiguracji polityk.

Aktualizacja 1.1 uwzględnia nowe decyzje użytkownika o MVP, analityce, Customer Support i UI/UX oraz pięć dostępnych tur dalszej rozmowy. Dwie odpowiedzi z tej części odczytu są ucięte; brakujących fragmentów nie traktujemy jako znanych ustaleń. Bieżące polecenie użytkownika jest podstawą zmian zakresu.

Aktualizacja 1.2 dodaje Business Knowledge & Decision Intelligence na podstawie bieżącego polecenia i pełnej dostępnej odpowiedzi o czterech źródłach wiedzy z dalszej rozmowy. Szczegóły są skupione w [05 — Decision Intelligence Architecture](05-DECISION-INTELLIGENCE-ARCHITECTURE.md).

We wszystkich pięciu plikach obowiązuje wspólna legenda:

| Oznaczenie | Znaczenie |
|---|---|
| **[D] Decyzja** | Kierunek wybrany przez użytkownika albo przyjęty w aktualnym zamówieniu dokumentacji. |
| **[U] Uzgodniony kierunek** | Konkretna propozycja z rozmowy przyjęta jako baza projektu, bez sugerowania formalnego zatwierdzenia każdego detalu. |
| **[P] Propozycja** | Doprecyzowanie projektowe dodane w tym pakiecie; wymaga przeglądu przed implementacją. |
| **[Z] Założenie** | Hipoteza rynkowa, przykładowe dane, limity lub parametr do walidacji. |
| **[O] Pytanie otwarte** | Rozstrzygnięcie potrzebne do wdrożenia lub kolejnej fazy. |

Jeżeli sekcja ma oznaczenie, dotyczy ono jej treści, chyba że wskazano inaczej. Wspólne identyfikatory `D-*`, `U-*`, `P-*`, `Z-*`, `O-*` ułatwiają śledzenie decyzji. Dokumenty są projektem, a nie deklaracją istniejącej implementacji. Zewnętrzne źródła wyjaśniają możliwości technologii; nie dowodzą potrzeb rynku ani skuteczności produktu.

## 2. Wizja i wartość produktu [D, U]

**D-01:** Pierwszym segmentem są małe i średnie sklepy DTC. Autonomous E-commerce Operator działa w tle: obserwuje sklep, wykrywa problemy i szanse, przygotowuje decyzje, realizuje dozwolone działania i sprawdza rezultat. Rozmowa z agentem jest interfejsem pomocniczym. Podstawowe procesy uruchamiają zdarzenia i harmonogramy.

**D-04:** Obsługujemy wyłącznie istniejące sklepy. Tworzenie sklepu, generowanie storefrontu i jego uruchamianie pozostają poza MVP. Onboarding oznacza podłączenie źródeł, konfigurację danych i polityk oraz uruchomienie monitoringu.

**D-05:** Zaawansowana analityka sprzedaży, rentowności, reklam i produktów jest pełnoprawną częścią MVP. **D-06:** Jeden interfejs dla początkujących i zaawansowanych stosuje progressive disclosure: proste podsumowanie → uzasadnienie → dane analityczne. Bez osobnych trybów użytkownika. Zasada „Simple by default. Powerful by design.” i szczegóły widoków mają jedno miejsce opisu: [UI/UX](04-UI-UX-SPECIFICATION.md).

Właściciel otrzymuje historię wykonanej pracy i kolejkę wyjątków. Wartość produktu mierzymy czasem odzyskanym przez zespół, skutecznością reakcji oraz wpływem na ekonomikę sklepu. Liczba wiadomości z modelem i liczba uruchomień agenta są kosztami lub metrykami diagnostycznymi, a nie wartością dla klienta.

**U-01:** Produkt łączy trzy mechanizmy: deterministyczne obliczenia i wykrywanie sygnałów, interpretację sytuacji przez AI oraz kontrolowane wykonanie przez backend. Każdy ma osobny kontrakt i testy. Proste obliczenie liczby dni zapasu nie wymaga modelu.

**D-08:** Podstawą jakości decyzji jest Decision Intelligence System: Domain Knowledge (Skills/Playbooks), Store Knowledge (cele i ekonomia sklepu), Operational Data (metryki i dowody) i Decision Experience (historia oraz obserwowane wyniki). Źródła pozostają niezależne od LLM i zasilają dotychczasowy bounded runtime. Model interpretuje, a Metrics & Evidence Engine wykonuje obliczenia finansowe i weryfikuje ich podstawy. Rekomendacja musi wskazywać dowody; cel wzrostu nie nadaje dodatkowych uprawnień.

**D-09:** W MVP utrwalamy wiedzę, profil sklepu, metryki/dowody, historię decyzji, podstawowe rezultaty i ewaluację offline. Agent może zalecić obserwację albo brak działania. Nie modyfikuje sam Skills, strategii ani polityk na podstawie wyników. Fine-tuning i autonomiczna adaptacja strategii są poza MVP; RAG/Vector DB są opcją po wykazaniu potrzeby.

## 3. Klient i walidacja segmentu [Z]

| Cecha | Roboczy profil klienta |
|---|---|
| Platforma pierwszej integracji | WooCommerce |
| Katalog | 10–500 SKU, z wariantami traktowanymi jako osobne jednostki magazynowe |
| Skala | 100–3000 zamówień miesięcznie |
| Reklama | 5–100 tys. PLN miesięcznie, głównie Meta Ads |
| Zespół | 1–10 osób, właściciel łączy funkcje operacyjne i marketingowe |
| Problem | Rozproszone dane, ręczne kontrole, opóźnione reakcje, ograniczony czas |
| Warunek udziału w pilocie | Dostęp do integracji i osoby odpowiedzialnej za polityki, mapowania oraz akceptacje |

To zakres do rozmów z klientami, nie potwierdzony opis rynku. **O-01:** Czy klient faktycznie chce delegować pauzowanie reklam i czy ma dostatecznie wiarygodne dane magazynowe? **O-02:** Czy WooCommerce zapewnia wystarczająco szybki onboarding wobec różnic wtyczek, hostingu i źródeł zapasu?

**[P] Walidacja:** kilka sklepów projektowych, obserwacja ich codziennej pracy, historyczny replay i shadow mode. Rejestrujemy czas ręcznej obsługi, fałszywe alarmy, brakujące dane oraz to, czy właściciel wybrałby proponowaną akcję. Model abonamentu według skali sklepu lub obsługiwanych procesów pozostaje hipotezą; cena nie jest ustalona.

## 4. Mapa procesów i zakres pierwszego wydania [U]

| Domena | Procesy | Zakres autonomii MVP / dalszy rozwój |
|---|---|---|
| Marketing | Monitoring kosztów, anomalii i wyników, kontrola reklam niedostępnych produktów | L0–L1; L2 dla jawnie dopuszczonej ochrony zapasu. Budżety i nowe kampanie później. |
| Zapasy i katalog | Zapas, tempo sprzedaży, ryzyko braku, mapowania SKU–reklama | L0–L2. Ceny, publikacja opisów i zakupy towaru poza MVP. |
| Zamówienia | Przekroczenie obiecanego czasu realizacji, brak zmiany statusu, eskalacje | L2 dla wewnętrznych zadań i alertów; anulowania i zwroty wymagają osobnej polityki. |
| Finanse i BI | Pełna analityka Sales, Profitability, Ads, Products, marża kontrybucyjna i diagnozy | W MVP, L0–L1 dla rekomendacji. Filtry, porównania, drill-down oraz jawna jakość metryk. |
| Customer Support | Konwersacje e-mail, standardowe odpowiedzi, powiązania z zamówieniami, eskalacje i przejęcie | W MVP: L1 dla szkiców, wybrane L2 dla zweryfikowanych odpowiedzi informacyjnych; pełny audyt. |
| CRM i retencja | Segmenty, follow-up, porzucone koszyki | Kolejna faza przez istniejący CRM/ESP; potrzebne zdarzenia koszyka i zgody. |

### Siedem procesów korzystających ze wspólnego rdzenia

| Proces | Wyzwalacz | Wynik | Tryb pierwszego wdrożenia |
|---|---|---|---|
| Profit Monitor | Harmonogram, zmiana KPI | Diagnoza rentowności i rekomendacja | L0–L1 |
| Ad Spend Guardian | Anomalia wydatków, zbliżenie do limitu | Alert i propozycja reakcji | L1; L2 dopiero po walidacji osobnej polityki |
| Inventory Guardian | Ryzyko braku zapasu | Alert lub pauza dedykowanej reklamy | L1 → wybrane L2 |
| Order Watchdog | Przekroczenie SLA realizacji | Zadanie i eskalacja wewnętrzna | L2 w granicach zatwierdzonych powiadomień |
| Integration Health | Błąd, opóźnienie, brak danych | Resynchronizacja i blokada zależnych akcji | L2 |
| Daily Business Briefing | Harmonogram w strefie sklepu | Podsumowanie i lista decyzji | L2 dla zatwierdzonego kanału wewnętrznego |
| Customer Support | Nowa wiadomość w skrzynce sklepu | Odpowiedź informacyjna lub podsumowanie, szkic i eskalacja do człowieka | L1 → wybrane L2 po konfiguracji zasad odpowiedzi |

**P-01:** Pierwszy pionowy wycinek implementacji obejmuje Inventory Guardian wraz z Integration Health i audytem. Pozostałe pięć procesów dokładamy po sprawdzeniu tego samego rdzenia. To kolejność dostarczania siedmiu procesów MVP, bez budowania osobnego systemu dla każdego.

### Customer Support w MVP [D, P]

**D-07:** Osobna domena obsługuje jeden kanał — e-mail istniejącego sklepu. Zakres obejmuje listę konwersacji i historię wiadomości, rozróżnienie odpowiedzi AI i człowieka, powiązania klient–zamówienie, standardowe odpowiedzi AI, eskalację z podsumowaniem i szkicem oraz przejęcie konwersacji przez operatora. Customer Support ma własną kolejkę spraw; Decision Inbox służy decyzjom biznesowym. Messenger, WhatsApp, Instagram i live chat pozostają poza MVP.

**[P] Granice:** L2 dotyczy odpowiedzi informacyjnych opartych na potwierdzonych danych, np. statusie zamówienia i katalogu. Reklamacje, niepewna tożsamość, brak danych i nietypowe żądania trafiają do człowieka. Szkic nie oznacza wysłanej wiadomości. Zmiany adresu, anulowania i zwroty nie zyskują automatyzacji przez samo dodanie Support; wymagają odrębnej akcji i polityki. Kompetencje, model domenowy i niezawodna wysyłka: [Customer Support Agent](02-AI-AGENT-ARCHITECTURE.md#14-customer-support-agent-d-p).

### Analityka w MVP [D, P]

Użytkownik ma dostęp do trendów i porównań okresów, tabel kampanii i SKU, kosztów oraz szczegółów obliczeń. Przy braku kosztów nadal działa analityka sprzedaży; rentowność pokazuje brakujące składniki. MVP nie obejmuje kreatora dowolnych raportów BI. Definicje metryk pozostają w sekcji 6; prezentacja, filtry i kontrakty są opisane w [widoku Analytics](04-UI-UX-SPECIFICATION.md#7-analytics-sales-profitability-ads-products).

## 5. Autonomia i odpowiedzialność [U]

| Poziom | Znaczenie | Przykład |
|---|---|---|
| L0 Observe | Analiza bez zmian w systemach zewnętrznych | Wykrycie ryzyka zapasu |
| L1 Recommend | Wykonanie konkretnej propozycji po akceptacji | Pauza wskazanej reklamy po sprawdzeniu zakresu |
| L2 Bounded Autonomy | Wykonanie w jawnej polityce, limitach i warunkach | Pauza jednej, zweryfikowanej reklamy danego SKU |
| L3 Delegated Autonomy | Planowanie i realizacja wieloetapowej strategii | Docelowa koordynacja zapasu i marketingu; poza MVP |

Poziom przypisujemy do typu akcji, zasobu i sklepu. Uprawnienie do raportowania nie daje uprawnienia do zmiany reklam. System rozpoczyna od L0/L1; właściciel osobno aktywuje L2 i określa zakres. Techniczny zapis audytu jest dozwolony także w L0; wysyłka powiadomienia to odrębna, uprzednio skonfigurowana operacja.

**D-02:** Model zwraca `DecisionProposal`. Backend tworzy `AuthorizedAction` dopiero po walidacji decyzji, polityki i aktualnego stanu. W MVP model widzi narzędzia odczytu; akcje wykonuje osobna warstwa. Akceptacja człowieka dotyczy dokładnej wersji propozycji i nie omija izolacji tenantów, utraty uprawnień, wygaśnięcia danych ani kill switcha.

**[P] Zasady działania:** przy nieaktualnym zapasie, niepewnym mapowaniu lub konflikcie system wstrzymuje zmianę zewnętrzną, zapisuje przyczynę i eskaluje. Kill switch zatrzymuje nowe mutacje globalnie, dla tenanta lub integracji; nie cofnie operacji już przyjętej przez zewnętrzne API. Przywrócenie reklamy jest nową decyzją. Nie stosujemy automatycznego „undo” po każdym błędzie procesu.

Poza autonomią MVP pozostają zwiększanie budżetów, masowe zmiany cen, zwroty środków, zobowiązania zakupowe, usuwanie danych i masowa komunikacja marketingowa. Można rozwijać je później jako osobne kontrakty, polityki i workflow.

## 6. Model biznesowy danych [U, P]

Centralnym obiektem jest `Opportunity`: wykryty problem lub szansa z dowodami, propozycją, decyzją polityki, przebiegiem wykonania i wynikiem. Dla czytelności rozdzielamy problemy biznesowe od prób ich obsługi:

| Obiekt | Odpowiedzialność |
|---|---|
| `Opportunity` | Jeden epizod problemu, np. ryzyko braku konkretnego wariantu |
| `Evidence` / `ContextSnapshot` | Wersjonowane obserwacje, źródła, jakość i czas |
| `AgentRun` | Konkretny przebieg analizy, model, skill, narzędzia, koszt |
| `DecisionProposal` | Typowane zalecenie, referencje do dowodów, ważność |
| `PolicyEvaluation` / `Approval` | Wynik reguł oraz ewentualna zgoda na konkretny zakres |
| `AuthorizedAction` / `ActionExecution` | Zaufane upoważnienie i trwały rejestr prób wykonania |
| `Outcome` | Potwierdzony stan techniczny i osobno obserwowany efekt biznesowy |

Customer Support dodaje własne `Conversation`, `Message`, `SupportCase`, `CustomerContext`, `Escalation`, `ResponseDraft`, `HumanAssignment` i `SupportPolicy`; ich odpowiedzialności i powiązania z kontraktami wspólnymi opisuje [architektura agenta Support](02-AI-AGENT-ARCHITECTURE.md#14-customer-support-agent-d-p). Każda wiadomość nie tworzy automatycznie nowej Opportunity; powiązana decyzja biznesowa może ją utworzyć.

**P-02:** `Opportunity.status`: `DETECTED → ANALYZED → PROPOSED → APPROVED → EXECUTING → VERIFIED → CLOSED`. Obsługujemy także `WAITING_APPROVAL`, `WAITING_DATA`, `NO_ACTION`, `REJECTED`, `EXPIRED`, `CONFLICT`, `FAILED`, `UNKNOWN` i `CANCELLED`. Szczegółowe przejścia opisuje [dokument AI](02-AI-AGENT-ARCHITECTURE.md#9-decyzje-polityki-i-akcje). Oczekiwanie na akceptację dotyczy propozycji, a nie dowolnego przyszłego działania w ramach problemu.

Ponawiany sygnał aktualizuje istniejący aktywny epizod. Nowa dostawa może zamknąć epizod, a kolejne ryzyko otworzyć następny. Unikalność aktywnego problemu egzekwuje baza. Identyfikatory zewnętrzne zawsze mają kontekst integracji i tenanta.

### Rentowność i jakość

**U-02:** Przybliżony model: `ContributionProfit = NetSales − COGS − Fulfillment − PaymentFees − AdSpend − OtherVariableCosts`. `NetSales` uwzględnia rabaty i zwroty; podatki i koszty muszą mieć spójną podstawę. To marża kontrybucyjna, nie zysk księgowy firmy. Nie odejmujemy zwrotów ponownie, jeśli uwzględniono je już w sprzedaży netto.

Dane oznaczamy jako `CONFIRMED`, `ESTIMATED` lub `UNAVAILABLE`. To informacja niezależna od świeżości i kompletności. Sumowanie konwersji raportowanych przez Meta i Google nie tworzy liczby unikalnych zamówień. Kosztu reklamowego SKU nie traktujemy jako potwierdzonego, gdy wynika z umownej alokacji wydatku kampanii wieloproduktowej. Brak COGS uniemożliwia rzetelną diagnozę marży, ale nie musi blokować Inventory Guardian, jeżeli jego reguły nie zależą od marży.

**[P] Kontrakty danych:** pieniądze jako liczby całkowite w jednostkach najmniejszych z walutą albo `BigDecimal`; czas w UTC, prezentacja i granice okresów w strefie sklepu. Osobno `sourceUpdatedAt`, `observedAt`, `ingestedAt`; brak zmiany źródłowego zasobu przez tydzień nie dowodzi, że jego odczyt jest nieaktualny. Każda metryka ma definicję, okres i wersję obliczenia.

Store Business Profile przechowuje wersjonowane cele `PROFITABILITY`, `GROWTH`, `BALANCED`, ekonomię i preferencje sklepu, z pochodzeniem i brakami danych. Twarde uprawnienia nadal egzekwuje Policy Engine. Szczegółowe definicje KPI, break-even ROAS, provenance oraz model profilu rozwija [05 — wiedza i metryki](05-DECISION-INTELLIGENCE-ARCHITECTURE.md#b-store-business-profile).

## 7. Architektura wysokopoziomowa [U]

```mermaid
flowchart TD
  Sources[WooCommerce / Meta Ads / e-mail] --> Integrations[Adaptery / webhooki / polling]
  Integrations --> Data[PostgreSQL: model commerce / inbox / outbox]
  Data --> Signals[Detektory sygnałów]
  Signals --> Opportunity[Opportunity]
  Opportunity --> Temporal[Temporal Workflow]
  Temporal --> Context[Knowledge & Context Builder]
  Knowledge[Domain / Store / Operational / Experience] --> Context
  Context --> Runtime[Bounded Agent Runtime]
  Runtime --> Gateway[ModelGateway / Spring AI]
  Runtime --> ReadTools[Read Tools]
  Runtime --> Proposal[DecisionProposal]
  Proposal --> Policy[Walidacja / Policy Engine / Approval]
  Policy --> Executor[Action Executor]
  Executor --> Meta[Meta Ads API / wysyłka e-mail]
  Meta --> Verify[Weryfikacja / Outcome / audyt]
  Verify --> UI[Control Center / powiadomienia]
```

**U-03:** Modularny monolit Spring Boot, z osobno uruchamianymi workerami Temporal, jeśli wymaga tego skalowanie lub izolacja. Jeden kod domenowy i granice modułów; osobny worker nie oznacza osobnego mikroserwisu dla każdego agenta. Nie zakładamy sieci stale rozmawiających agentów.

| Moduł | Zakres |
|---|---|
| Identity / Tenants | Użytkownicy, role, sklepy, powiązania integracji |
| Integrations | WooCommerce, Meta Ads, skrzynka e-mail Support, opcjonalny Slack, zdrowie synchronizacji |
| Commerce | Katalog, warianty, zamówienia, zapasy, finanse i metryki |
| Customer Support | Konwersacje, wiadomości, kontekst klienta, szkice, eskalacje i przypisania |
| Intelligence | Sygnały, kontekst, runtime, skills, proposals i routing modeli |
| Automation | Polityki, akceptacje, akcje, Temporal i blokady konfliktów |
| Reporting / Audit | Historia decyzji, statusy, efekty, briefing i telemetria |

Reporting udostępnia modele odczytu analityki i trzy poziomy informacji z tych samych dowodów, bez niezależnego wyliczania liczb przez LLM. Integracja e-mail obejmuje odbiór, wysyłkę i uzgadnianie statusów. Dostawcę wybieramy w O-11; kanał korespondencji z klientami jest odrębny od wewnętrznych powiadomień operatora.

Integracje tłumaczą API dostawcy na kontrakty domenowe. `InventoryService` ani skill nie znają formatu odpowiedzi WooCommerce. Zewnętrzne tokeny pozostają w adapterach. Panel komunikuje się z backendem, a nie bezpośrednio z modelem w celu wykonywania akcji.

### Decision Intelligence jako rozszerzenie istniejących modułów [D, P]

| Komponent | Miejsce i zakres rozszerzenia |
|---|---|
| Knowledge Registry | Intelligence: wspólny rejestr Skills, Playbooks i referencji; obecny SkillRegistry jest jego częścią |
| Store Business Profile | Business Context: wersjonowana strategia, ekonomia i ograniczenia sklepu |
| Metrics & Evidence Engine | Commerce/Reporting: wspólne obliczenia, jakość, aktualność i lineage dowodów |
| Knowledge & Context Builder | Rozwinięcie obecnego Context Builder o cztery źródła wiedzy |
| Decision History | Decisions/Audit: spójna projekcja rekomendacji, zgód, akcji i rewizji |
| Outcome Evaluator | Reporting/Evaluation: obserwowane rezultaty, okna i ograniczenia interpretacji |
| Offline Evaluation Framework | Intelligence/Evaluation: historyczne scenariusze i porównywanie wersji przed wydaniem |

To odpowiedzialności w modularnym monolicie, nie nowe usługi. Temporal nadal realizuje procesy. Spadek ROAS/zmiana CPA uruchamia analizę uwzględniającą próbę, opóźnienia konwersji, atrybucję, fazę uczenia i historię zmian, a nie automatyczne cięcie kampanii. Rezultat po akcji jest obserwacją, dopóki odpowiedni projekt pomiaru nie uzasadni wniosku przyczynowego. Kontrakty i pełna ścieżka: [05 — Decision Intelligence](05-DECISION-INTELLIGENCE-ARCHITECTURE.md).

### Synchronizacja i niezawodność [P]

Webhook zostaje zweryfikowany i trwale zapisany w inbox przed potwierdzeniem odbioru. Normalizacja danych i zapis zdarzenia outbox następują w jednej transakcji PostgreSQL. Dispatcher przekazuje zdarzenia do Temporal z trwałym identyfikatorem; powtórka jest spodziewana i deduplikowana. Nie ma transakcji obejmującej jednocześnie PostgreSQL, Temporal i Meta.

WooCommerce: webhooki zamówień i produktów, uzupełniający polling wariantów i okresowe reconciliation. Meta: polling konfiguracji i Insights z kontrolą limitów oraz oknem ponownego pobierania zmieniających się historycznych wyników. Harmonogramy są parametrami pilota, nie obietnicą natychmiastowości. Oficjalne kontrakty dostępne są w [WooCommerce REST API](https://woocommerce.github.io/woocommerce-rest-api-docs/).

Temporal utrzymuje stan procesów, oczekiwania i retry. Własne inbox/outbox utrzymują granicę integracji i spójność danych. **Nie zakładamy exactly-once dla zewnętrznego skutku:** Activity może wykonać się ponownie, dlatego rejestr akcji i odczyt stanu są częścią projektu. Zob. [Temporal — idempotencja Activities](https://docs.temporal.io/activity-definition#idempotency).

## 8. Technologie [U, P]

| Obszar | Kierunek | Status |
|---|---|---|
| Backend | Java + Spring Boot | Uzgodnione; dokładna para wersji do testu kompatybilności |
| AI | Własny runtime → `ModelGateway` → Spring AI → adapter modelu | Decyzja D-03 z bieżącego zakresu |
| Dane | PostgreSQL, migracje schematu, transakcje, inbox/outbox | Uzgodnione |
| Workflow | Temporal + Java SDK | Uzgodnione |
| Panel | Next.js / React | Uzgodniony kierunek |
| Telemetria | OpenTelemetry, metryki i dashboardy Grafana | Uzgodniony kierunek |
| Pliki | S3-compatible storage dla większych snapshotów i artefaktów | Opcjonalne w pilocie |
| Sekrety | Zarządzany magazyn sekretów, szyfrowanie i rotacja | Propozycja wdrożeniowa |
| Redis | Cache i limity, jeśli potrzebne | Opcjonalny; nie jedyne źródło poprawności blokad |
| RabbitMQ / Kafka | Bez nich w MVP | Uzgodnione; dodać po wykazaniu potrzeby |
| n8n | Poza core MVP | Uzgodnione; późniejsze integracje wymagają oceny produktu i licencji |
| MCP | Opcjonalny adapter narzędzi | Bez obowiązkowego serwera MCP w pierwszym wdrożeniu |

W rozmowie rozważano Java 21/25 oraz Spring AI/LangChain4j. Aktualny pakiet wybiera Spring AI, a wersji Javy i Spring Boot nie zamraża bez sprawdzenia zgodności wybranej stabilnej linii. Dokumentacja Spring AI jest wersjonowana; nie kopiujemy ustawień między liniami bez testu. Niezależność od dostawcy oznacza własne kontrakty i testy zachowania, nie identyczną jakość wszystkich modeli. Szczegóły: [gateway i runtime](02-AI-AGENT-ARCHITECTURE.md#4-modelgateway-i-spring-ai).

## 9. Wymagania MVP i warunki odbioru [P]

| ID | Wymaganie | Sprawdzalny warunek |
|---|---|---|
| F-01 | Onboarding istniejącego WooCommerce, Meta, skrzynki Support i kanału wewnętrznego | Test połączenia, zakresów dostępu, synchronizacji i właściciela konta; brak tworzenia sklepu |
| F-02 | Ujednolicone dane i mapowania | Użytkownik widzi źródło, świeżość, niekompletność i potwierdza relacje SKU–reklama |
| F-03 | Lista Opportunity i decyzji | Widać dowody, uzasadnienie, politykę, ważność i wynik |
| F-04 | L0/L1/L2 per akcja | W L0/L1 bez zgody nie występuje mutacja Meta; L2 działa wyłącznie w zakresie |
| F-05 | Akceptacje i kill switch | Powtórzone kliknięcie nie duplikuje akcji; zmiana propozycji unieważnia zgodę |
| F-06 | Siedem wspólnych procesów | Dostarczane etapami z tabeli procesów; każdy ma wynik i właściciela eskalacji |
| F-07 | Pełna analityka MVP | Cztery zakładki, daty/porównania, drill-down, definicje i jakość metryk |
| F-08 | Konwersacje i odpowiedzi e-mail | Historia, zamówienie, szkic, eskalacja, takeover i status wysyłki; brak równoległej odpowiedzi AI/człowieka |
| F-09 | Jedno UI, trzy poziomy informacji | Przejście podsumowanie–uzasadnienie–analiza zachowuje produkt, daty i źródło decyzji |
| F-10 | Decision Intelligence w MVP | Decyzja wskazuje wersje wiedzy/profilu, dowody i plan obserwacji; MONITOR różni się od NO_ACTION; każda nowa wersja wiedzy przechodzi ewaluację offline |
| N-01 | Izolacja tenantów | Testy obejmują API, tools, SQL, snapshoty, kolejki i workery |
| N-02 | Odporność na powtórki i restarty | Odtworzenie procesu nie powoduje dodatkowej zmiany biznesowej |
| N-03 | Audyt | Każda mutacja ma dowody, policy evaluation, upoważnienie i wynik weryfikacji |
| N-04 | Kontrola kosztów AI | Przebieg zatrzymuje się po limicie; koszt i przyczyna zatrzymania są widoczne |
| N-05 | Dane i sekrety | Tokeny i zbędne dane osobowe nie trafiają do promptów ani logów |
| N-06 | Widoczna degradacja | Niedostępny model lub API nie tworzy fałszywego `VERIFIED` |

**Z-01:** Cele pilota do potwierdzenia: 95% kwalifikujących się sygnałów analizowanych w 5 minut od trwałego przyjęcia; 95% zaakceptowanych pauz potwierdzonych do 2 minut, z wyłączeniem oczekiwania na człowieka. Awarie i limity źródła są raportowane osobno, a nie ukrywane w mianowniku. Budżet AI oraz maksymalna liczba mutacji zależą od sklepu. Konkretne parametry scenariusza nie stanowią SLA produktu.

Dane osobowe ograniczamy do niezbędnych dla procesu; Inventory Guardian nie potrzebuje imion, adresów ani treści wiadomości klientów. **O-03:** Region przetwarzania, retencja, umowy z dostawcami i zakres obowiązków ochrony danych wymagają uzgodnienia przed produkcją. To wymagania projektowe, nie opinia prawna.

Customer Support potrzebuje ograniczonego kontekstu klienta, wiadomości i powiązania zamówienia. Minimalizacja, kontrola dostępu i retencja obejmują również prompty, załączniki i audyt. Odczyt zamówienia wymaga zweryfikowanego powiązania klienta; samo podanie numeru zamówienia w e-mailu nie wystarcza.

## 10. Fazy i kryteria przejścia [P]

| Faza | Dostarczany rezultat | Warunek przejścia |
|---|---|---|
| 0. Walidacja | Sklepy projektowe, dostęp do danych, ręczna mapa procesu | Potwierdzony problem, właściciel decyzji i możliwość bezpiecznego mapowania |
| 1. Fundament danych | Integracje, normalizacja, inbox/outbox, health, panel jakości i modele analityczne MVP | Reconciliation wykrywa braki; warianty i rezerwacje mają uzgodnioną semantykę |
| 2. Shadow Inventory Guardian | Runtime, skill, Opportunity, polityki bez mutacji | Przegląd błędów i testów historycznych; brak nieautoryzowanych akcji |
| 3. L1 | Pauzy po akceptacji, Temporal, weryfikacja i kill switch | Crash/retry, konflikty i niejednoznaczny wynik obsłużone w testach |
| 4. Ograniczone L2 | Wybrane dedykowane reklamy i limity pilota | Jawne opt-in, stabilne dane, zaakceptowany poziom fałszywych decyzji |
| 5. Domknięcie MVP | Profit/Spend/Order/Briefing, Customer Support e-mail i pełne cztery zakładki Analytics | Polityki procesów, takeover i wysyłka Support, spójność analityki z dowodami decyzji |
| 6. Rozszerzenia | Shopify, Google Ads, CRM, wielokanałowy support, wybrane delegacje | Potwierdzona wartość i własne kontrakty danych/wykonania |

Analityka i Customer Support są wymaganiami odbioru MVP, nawet jeśli wdrażane przyrostowo. Sekwencja pionowego wycinka nie przenosi ich do fazy rozszerzeń.

Decision Intelligence jest częścią tych samych faz: profil i Metrics & Evidence Engine w fundamencie danych, wersjonowana wiedza i testy offline przed shadow/L1, Outcome Tracking od pierwszej wykonanej akcji. Nie stanowi osobnej fazy po MVP.

Nie ustalamy kalendarza bez informacji o zespole i dostępności kont testowych. Dostępność API, zgody i jakość danych są zależnościami planu, nie zadaniami ukrytymi pod „podłączenie integracji”. Wspomniany w rozmowie projekt `ecommerce-flow` może dostarczyć kod lub doświadczenia; jego zawartości tutaj nie sprawdzono, więc reuse nie jest założeniem harmonogramu.

### Pomiar wartości

Mierzymy czas ręcznej pracy przed i po wdrożeniu, udział rozstrzygniętych Opportunity, czas wykrycie–reakcja, odsetek zaakceptowanych propozycji, fałszywe blokady i akcje wymagające naprawy, skuteczność technicznej weryfikacji oraz koszt obsługi procesu. Zmiana wydatków po pauzie nie jest automatycznie „oszczędnością netto”: uwzględniamy utraconą sprzedaż, inne kanały i brak kontrfaktycznego pomiaru. Wynik biznesowy opisujemy jako obserwację lub estymację, dopóki eksperyment nie pozwala przypisać przyczyny.

## 11. Rejestr decyzji i pytań

| ID | Status | Treść / dalsze działanie |
|---|---|---|
| D-01 | Decyzja | Małe i średnie DTC jako segment startowy |
| D-02 | Decyzja | `DecisionProposal`, backendowe `AuthorizedAction`, ograniczone wykonanie |
| D-03 | Decyzja | Własny bounded runtime i gateway Spring AI, przenośne Skills/Tools/Policies/Workflows |
| D-04 | Decyzja 1.1 | Wyłącznie istniejące sklepy, bez ich tworzenia i uruchamiania |
| D-05 | Decyzja 1.1 | Zaawansowana analityka czterech obszarów w MVP |
| D-06 | Decyzja 1.1 | Jedno UI, progressive disclosure; bez trybów początkujący/ekspert |
| D-07 | Decyzja 1.1 | Customer Support e-mail jako osobna domena i proces MVP |
| D-08 | Decyzja 1.2 | Cztery źródła wiedzy i deterministyczna podstawa decyzji niezależna od LLM |
| D-09 | Decyzja 1.2 | Knowledge/Profile/Metrics/History/Outcome/Offline Evaluation w MVP, bez samodzielnej zmiany strategii |
| U-01 | Kierunek | Reguły + interpretacja AI + kontrolowane wykonanie |
| U-02 | Kierunek | Wspólny model danych i jakość metryk |
| U-03 | Kierunek | Modularny monolit, Temporal, PostgreSQL; WooCommerce + Meta + kanał wewnętrzny; D-07 dodaje skrzynkę Support |
| P-01 | Propozycja | Inventory Guardian jako pierwszy pionowy wycinek |
| P-02 | Propozycja | Wspólne obiekty i stany opisane w tym pakiecie |
| O-04 | Otwarte, blokuje L2 | Kto i jak potwierdza SKU–reklama; jak szybko wykrywamy zmianę kreacji? |
| O-05 | Otwarte, blokuje L2 | Które źródło ustala zapas sprzedawalny i czy rezerwacje są już odjęte? |
| O-06 | Otwarte, blokuje produkcję | Uprawnienia i testowe konto Meta, wersja API, ograniczenia obsługiwanych reklam |
| O-07 | Otwarte | Dostawcy/model policy, rezydencja danych, budżety i kryteria ewaluacji |
| O-08 | Otwarte | Definicje COGS, podatków, zwrotów i alokacji reklam dla Profit Monitor |
| O-09 | Otwarte | Kanał Slack czy e-mail, role akceptujące, eskalacja po czasie |
| O-10 | Otwarte | Konkretne wersje stacku, hosting Temporal i operacyjne utrzymanie |
| O-11 | Otwarte, blokuje wysyłkę Support | Dostawca/integracja skrzynki, odczyt Sent, statusy i zachowanie po niejednoznacznym wysłaniu |
| O-12 | Otwarte, blokuje L2 Support | Weryfikacja klient–zamówienie, zatwierdzone kategorie odpowiedzi i źródła polityk sklepu |
| O-13 | Otwarte | Role Support, terminy reakcji na eskalacje i retencja wiadomości/załączników |
| O-14 | Otwarte | Docelowy wolumen i czas odpowiedzi analityki, zakres eksportów; szczegóły w UI/UX |
| O-18–O-23 | Otwarte, nowe w 1.2 | Właściciele wiedzy, profil i konflikty, definicje/maturity metryk, migracja kontraktu decyzji, outcome windows i benchmarki; centralny rejestr w dokumencie 05 |

Implementacja zasad AI: [02 — Architektura AI](02-AI-AGENT-ARCHITECTURE.md). Przykłady danych, polityk, awarii i testów: [03 — Inventory Guardian](03-INVENTORY-GUARDIAN-E2E.md).

## Zmiany w wersji 1.1

Dodano decyzje D-04–D-07, Customer Support e-mail jako siódmy proces, pełną analitykę MVP i zasadę jednego UI. Uzupełniono moduły, wymagania, fazy i kwestie otwarte; zachowano dotychczasowy rdzeń architektury. Szczegóły ekranów przeniesiono do [04 — UI/UX](04-UI-UX-SPECIFICATION.md).

## Zmiany w wersji 1.2

Dodano D-08/D-09 i wysokopoziomową warstwę Decision Intelligence, bez zmiany granic wykonania. Uzupełniono model profilu, wymagania i fazy MVP; szczegółowe kontrakty, Marketing Knowledge Pack i pytania O-18–O-23 znajdują się w [05](05-DECISION-INTELLIGENCE-ARCHITECTURE.md).
