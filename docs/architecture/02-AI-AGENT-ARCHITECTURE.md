# Autonomous E-commerce Operator — szczegółowa architektura AI

Wersja: 1.2 · Data: 14 listopada 2026 · Status: projekt do przeglądu

Powiązania: [koncepcja produktu i MVP](01-PRODUCT-VISION-AND-ARCHITECTURE.md) · [Inventory Guardian end-to-end](03-INVENTORY-GUARDIAN-E2E.md) · [UI/UX i API widoków](04-UI-UX-SPECIFICATION.md).

Obowiązuje legenda i ograniczenie dostępności materiału źródłowego z [dokumentu produktu](01-PRODUCT-VISION-AND-ARCHITECTURE.md#1-podstawa-i-sposób-czytania): **[D]** decyzja, **[U]** uzgodniony kierunek, **[P]** nowe doprecyzowanie, **[Z]** założenie, **[O]** pytanie otwarte. Kontrakty i limity poniżej są **[P]**, o ile nie oznaczono inaczej. Fragmenty Java i pseudokod pokazują granice architektury; nie stanowią kompletnego, kompilowalnego projektu.

## 1. Zasada architektoniczna [D, U]

**D-03:** Budujemy własny bounded Agent Runtime z własnym `ModelGateway`; Spring AI jest podstawowym adapterem do modeli. **D-02:** LLM proponuje decyzję, backend kontroluje jej dopuszczalność i wykonanie. Agent może wybrać kolejne narzędzie analityczne w ramach zadania. Workflow wyznacza trwały proces biznesowy i jego etapy.

Model działania przenosi poznane wzorce z Anthropic Architect Foundations na poziom aplikacji: tool design, proceduralne Skills, dobór kontekstu, lifecycle hooks i delegacja. Nie zakładamy dostępu do pełnej treści kursu ani zgodności z konkretnym SDK. Oficjalny opis Anthropic rozróżnia przepływy o ustalonej strukturze i agentów dynamicznie dobierających kroki; wykorzystujemy to rozróżnienie w projekcie. [Źródło: Building effective agents](https://www.anthropic.com/research/building-effective-agents).

| Element | Rola | Przykład |
|---|---|---|
| Agent | Profil decyzyjny uruchomiony dla zadania | Inventory Agent |
| Skill | Wersjonowana procedura analizy | `inventory-guardian@1.0.0` |
| Tool | Typowana operacja domenowa | `get_inventory_forecast` |
| Policy | Egzekwowalna reguła uprawnień i ryzyka | Pauza tylko dedykowanej, zweryfikowanej reklamy |
| Workflow | Trwały cykl obsługi problemu | Sygnał → analiza → zgoda → akcja → weryfikacja |
| Hook | Punkt cyklu życia uruchamiający kontrolę | `BeforeToolCall` |
| MCP | Protokół dostarczenia narzędzi i kontekstu | Opcjonalny zewnętrzny CRM |
| Decision | Utrwalona propozycja i jej rozstrzygnięcie | `DecisionProposal` + `PolicyEvaluation` |

Skill opisuje postępowanie, tool wykonuje operację, policy egzekwuje granice. Instrukcja „nie zmieniaj reklamy bez zgody” w skillu jest użyteczna dla jakości odpowiedzi, ale wykonawczą gwarancję daje API backendu.

## 2. Warstwy i granice zależności [U, P]

```mermaid
flowchart TD
  WF[Temporal Workflow] --> A[Agent Activity]
  A --> RT[AgentRuntime]
  RT --> AR[AgentRegistry]
  RT --> SR[Knowledge Registry / SkillLoader]
  RT --> CB[Knowledge & Context Builder]
  RT --> MG[ModelGateway]
  MG --> SAI[SpringAiGateway]
  SAI --> Providers[Dostawcy LLM]
  RT --> TD[ToolDispatcher / Hooks]
  TD --> Native[Native Read Tools]
  TD --> MCP[MCP Adapter]
  RT --> OV[OutputValidator]
  OV --> DP[DecisionProposal]
  DP --> DE[Decision Service]
  DE --> PE[Policy Engine / Approval]
  PE --> AE[Action Executor]
  AE --> API[Zewnętrzne API]
```

Warstwa Intelligence zawiera profile, skills, kontekst, runtime i walidację. Business & Execution zawiera Opportunity, decyzje, polityki, approvals, Action Registry i Temporal. Integration & Data zawiera adaptery, normalizację, PostgreSQL i metryki. Typy SDK dostawców oraz klasy Spring AI nie przenikają do kontraktów domenowych.

Aktualizacja D-08/D-09 wprowadza cztery źródła Decision Intelligence. Nie dodaje drugiego runtime ani odrębnych mikroserwisów. Rozszerzenia odpowiedzialności opisuje sekcja 16, a szczegóły i kontrakty — [05 — Decision Intelligence Architecture](05-DECISION-INTELLIGENCE-ARCHITECTURE.md).

**[P] Pakiety:** `intelligence.runtime`, `intelligence.skills`, `intelligence.tools`, `intelligence.models`, `intelligence.context`, `intelligence.decisions`; `automation.policies`, `automation.approvals`, `automation.actions`, `automation.workflows`; `integrations.*`, `commerce.*`, `audit`. Zależności kontrolujemy testem architektonicznym. Łączenie logiki marketingowej z obiektami HTTP Meta ograniczamy do adaptera.

## 3. Profile i Agent Runtime

### AgentDefinition

```java
public record AgentDefinition(
    String name,
    String version,
    String systemPromptRef,
    Set<String> availableSkills,
    Set<String> allowedReadTools,
    ModelPolicy modelPolicy,
    RunLimits limits,
    String outputSchemaVersion
) {}
```

Uzgodnione profile: Marketing Agent, Inventory Agent, Operations Agent i Business Analyst; decyzja D-07 dodaje Customer Support Agent. Wspólnie korzystają z runtime; aktywujemy tylko kompetencje potrzebne we wdrożonym procesie. Profil nie jest mikroserwisem ani osobnym magazynem pamięci. Kompetencje nowego profilu opisuje sekcja 14.

Przykładowa konfiguracja **[Z]**:

```yaml
agent:
  name: inventory-agent
  version: "1.0.0"
  availableSkills: ["inventory-guardian@1.0.0"]
  allowedReadTools:
    - get_inventory_forecast
    - get_replenishment_plan
    - get_product_ad_mappings
    - get_ad_delivery_state
    - get_recent_actions
  modelPolicy: INVENTORY_BALANCED
  outputSchemaVersion: "1.0"
  limits:
    maxModelCalls: 5
    maxToolCalls: 8
    maxWallTimeSeconds: 60
    maxInputTokens: 12000
    maxOutputTokensTotal: 4000
    maxEstimatedCostUsd: "0.10"
    maxOutputRepairCalls: 1
```

Limity dotyczą całego przebiegu, łącznie z naprawą JSON i fallbackiem. Rezerwujemy koszt przed wywołaniem, rozliczamy raportowane usage po odpowiedzi. Szacunek kosztu wymaga wersjonowanego cennika; przykład nie jest ofertą żadnego modelu. Nieznane usage oznaczamy jako niepełne. Przekroczenie dowolnego limitu kończy analizę kontrolowanym `RunFailure` lub zatwierdzonym wynikiem `WAIT_FOR_DATA`; nie generuje automatycznie pauzy.

### Własna pętla

1. Backend ustala zaufany `RunContext`: tenant, sklep, integracje, role, identyfikatory Opportunity i snapshotu. Model nie podaje tych wartości jako autoryzacji.
2. Runtime ładuje wersję profilu, metadata Skills i wybraną procedurę. Dla Inventory Guardian wybór jest deterministyczny z typu workflow.
3. Context Builder składa minimalny kontekst z dowodami; registry wyznacza część wspólną uprawnień profilu, skills, tenanta i stanu integracji.
4. `BeforeModelCall` sprawdza budżet, deadline i kontekst. Gateway wykonuje jeden krok modelu.
5. Runtime sprawdza, czy wynik jest kompletny, poprawny i mieści się w limicie. Ucięta odpowiedź nie jest kompletnym wywołaniem narzędzia ani decyzją.
6. Dla każdego tool call waliduje nazwę, argumenty, zakres zasobów i permissions; uruchamia hooks, tool oraz walidację wyniku. Powiązania `toolCallId` zachowuje w historii.
7. Wyniki wracają jako dane narzędziowe do następnego kroku. Niezależne odczyty można równoleglić w limitowanej puli; zależne wykonujemy kolejno.
8. Wynik końcowy przechodzi JSON Schema i walidację domenową. Backend nadaje identyfikatory i zapisuje proposal. Wszystkie błędy kończą się jawnym stanem.

Model może zakończyć analizę wcześniej. Duplikat tego samego odczytu w jednym snapshotcie może korzystać z cache o odpowiedniej świeżości. Powtarzanie identycznych błędnych wywołań zużywa limit i kończy przebieg. `ToolDispatcher` nigdy nie wywołuje dowolnej metody przez nazwę dostarczoną przez model.

## 4. ModelGateway i Spring AI

```java
public interface ModelGateway {
    ModelCapabilities capabilities(ModelId modelId);
    ModelTurn complete(ModelRequest request);
}
```

| Kontrakt | Wymagane informacje |
|---|---|
| `ModelRequest` | Zaufany run ID, uporządkowane wiadomości, definicje tools, output schema, model policy, deadline, limity |
| `ModelTurn` | Pełne tool calls lub końcowa treść, finish reason, usage, identyfikator żądania dostawcy |
| `ToolCall` | Opaque ID dostawcy, nazwa z registry, argumenty JSON, informacja o kompletności |
| `ModelCapabilities` | Tool calling, obsługiwany podzbiór JSON Schema, structured output, limity wejścia/wyjścia, streaming i opcje modeli |
| `ModelFailure` | Typ błędu, retryability, retry-after, provider request ID; bez tokenów i surowych sekretów |

**D-03:** `Domain Runtime → ModelGateway → SpringAiGateway → Provider`. W razie brakującej funkcji Spring AI dopuszczamy dedykowany adapter za tym samym portem. **[O]** Pierwszy dostawca i konkretne modele zależą od ewaluacji; nie zamrażamy nazw modeli ani aktualnych cen w projekcie.

Spring AI zapewnia integracje i mechanizmy tool calling, ale jego domyślna automatyczna pętla nie może wykonać narzędzia poza kontrolą runtime. Adapter ma zwrócić żądanie tool call do naszej pętli. W wybranej wersji wyłączamy automatyczne wykonanie i testujemy ten warunek. Bieżąca dokumentacja opisuje tryb user-controlled; nazwy ustawień różnią się między liniami. [Źródło: Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html).

### Macierz możliwości i fallback

| Właściwość | Wymóg i zachowanie |
|---|---|
| Tool calling | Wymagany dla profilu z dynamicznym doborem tools; brak oznacza wykluczenie modelu z tej ścieżki |
| Native structured output | Preferowany; walidacja lokalna obowiązuje zawsze |
| Schemat bez natywnego wsparcia | Ograniczony tryb JSON + walidacja i najwyżej jedna naprawa; dopuszczenie po ewaluacji |
| Tool calling razem ze schematem | Jeśli nieobsługiwane, finalny krok bez tools generuje proposal z tych samych dowodów |
| Różne role i formaty wiadomości | Adapter mapuje jawnie; runtime przechowuje format kanoniczny i identyfikatory wywołań |
| Brak usage lub limitu kontekstu | Konserwatywny lokalny limit, jawne oznaczenie estymacji |
| Timeout / throttling / niedostępność | Ograniczony retry w deadline, potem fallback lub failure |

Routing filtruje modele według wymaganych możliwości, zgód na przetwarzanie danych, jakości na zestawie ewaluacyjnym, dostępności i kosztu. Wyższe ryzyko nie oznacza automatycznie „większego modelu” ani szerszych uprawnień.

**[P] Fallback między dostawcami rozpoczyna nową próbę analizy na tym samym kanonicznym snapshotcie.** Nie przenosimy niezamkniętych tool calls między różnymi protokołami. Rejestrujemy model każdej próby i wspólny limit kosztu. Po zapisaniu zaakceptowanej propozycji fallback nie może jej nadpisać. Zmiana wyniku tworzy nową rewizję i wymaga nowej oceny polityki/zgody.

Test granicy: sztuczny model zwraca `pause_ad`; gateway nie wykonuje go, a dispatcher odrzuca jako narzędzie spoza read allowlist. Test drugi: model zwraca tool call, a jedyny licznik wykonania zwiększa się dopiero w naszym dispatcherze. Zapobiega to ukrytej drugiej pętli.

## 5. Tool Registry i projektowanie narzędzi

```java
public interface AgentTool<Input, Output> {
    ToolDefinition definition();
    Output execute(ToolExecutionContext context, Input input);
}
```

Registry przechowuje `name`, `version`, opis zastosowania i ograniczeń, `inputSchema`, `outputSchema`, permissions, `effectType`, timeout, limit rozmiaru, zasady cache, klasyfikację danych oraz implementację. Efekty: `READ`, `PROPOSE`, `MUTATE`. **D-02:** W MVP pętla modelu dostaje tylko `READ`; propozycja jest wynikiem modelu, a `MUTATE` znajduje się w Action Registry wykonawcy.

`ToolExecutionContext` ustala backend. Nie ma `tenantId`, tokenu ani dowolnego URL w argumentach widocznych dla modelu. Nawet poprawny identyfikator reklamy musi zostać rozwiązany w zakresie tenanta i integracji. Błędy narzędzi mają kod (`NOT_FOUND`, `FORBIDDEN`, `STALE_DATA`, `RATE_LIMITED`, `UNAVAILABLE`), bezpieczny opis oraz informację o ponowieniu.

| Narzędzie | Wejście | Wynik i ograniczenia |
|---|---|---|
| `get_inventory_forecast` | `productVariantId`, `asOf` | Zapas sprzedawalny, prędkość sprzedaży, dni pokrycia, metoda, jakość, evidence IDs |
| `get_replenishment_plan` | `productVariantId` | Dostawy, ilości, ETA, status potwierdzenia; brak integracji daje `UNAVAILABLE` |
| `get_product_ad_mappings` | `productVariantId` | Mapowania do ads, zakres produktu, metoda potwierdzenia i fingerprint kreacji |
| `get_ad_delivery_state` | `adId` | Konto, campaign/adset, skonfigurowany i efektywny status, czas odczytu |
| `get_recent_actions` | `resourceId`, ograniczony okres | Akcje operatora i wykryte zmiany zewnętrzne, aktywne blokady |
| `get_campaign_performance` | `campaignId`, okres | Agregaty kosztów i wyników z metadanymi atrybucji |
| `get_product_profitability` | `productVariantId`, okres | Marża i alokacje albo jawne braki; bez pozornej dokładności |

Ostatnie dwa są kompetencjami marketingowo-finansowymi; Inventory Agent nie dostaje ich bez potrzeby. Obliczenia agregatów wykonuje kod, model interpretuje wyniki. Zwracamy ograniczone zbiory i sumaryczne metryki, zamiast tysięcy rekordów zamówień. Parametry okresów, ID i enumeracji mają zamknięte schematy; `additionalProperties: false` eliminuje nieznane pola na granicach.

Wynik odczytu ma `snapshotId`, `observedAt`, `sourceUpdatedAt`, `completeness`, `quality`, `evidenceIds` i dane. Jeśli tool pozyska nowszą obserwację, Context Builder tworzy następną rewizję snapshotu albo dopina jawnie nowy dowód. Proposal wskazuje dokładną wersję używanego zestawu; nie łączymy ukradkiem niespójnych czasowo stanów.

## 6. Skills: procedury, wersje i ładowanie

**U-04:** Skill jest podstawową jednostką rozszerzania kompetencji. Własny loader działa dla wszystkich dostawców: metadata do wyboru, pełna instrukcja po wyborze, referencje tylko w razie potrzeby. Format `SKILL.md` i opcjonalne katalogi inspirujemy otwartą [specyfikacją Agent Skills](https://agentskills.io/specification). Dodatkowe ograniczenia runtime są naszym kontraktem.

```text
skills/
  inventory-guardian/
    SKILL.md
    manifest.yaml
    references/
      inventory-semantics.md
      ad-mapping-rules.md
    examples/
      pause-single-product.json
      ambiguous-catalog.json
    evals/
      cases.jsonl
```

Przykładowy frontmatter:

```yaml
---
name: inventory-guardian
description: Analizuje ryzyko braku zapasu i dobiera reakcję dla powiązanych reklam.
metadata:
  version: "1.0.0"
  owner: "commerce-operations"
---
```

Wersję umieszczamy w `metadata`, zamiast zakładać, że dowolne pole `version` jest częścią standardu. Własny `manifest.yaml` wiąże skill z output schema, wymaganymi read tools, zakresem danych i zestawem ewaluacyjnym. Treść procedury dla tego przykładu znajduje się w [scenariuszu E2E](03-INVENTORY-GUARDIAN-E2E.md#5-skill-i-prompt).

**[P] Lifecycle:** szkic → review biznesowy i techniczny → ewaluacja na stałych przypadkach → shadow → aktywacja wersji → monitoring → rollback. Registry przechowuje niezmienny hash paczki. Każdy `AgentRun` zapisuje przypięte wersje skill, promptu, tools, schematu i model policy. Aktualizacja nie zmienia trwającej analizy ani zatwierdzonej propozycji.

MVP ładuje wyłącznie zatwierdzone paczki aplikacji. Skill nie zwiększa uprawnień profilu i nie definiuje egzekwowalnej polityki. Pliki instrukcji nie mogą wykonywać arbitralnych skryptów; obliczenia i integracje przechodzą przez zarejestrowany kod backendu. Procedury od klienta lub zewnętrznego repozytorium wymagałyby odrębnego procesu dopuszczenia. Nie są częścią pierwszego wydania.

## 7. MCP i integracje [U, P]

MCP jest opcjonalnym sposobem dostarczenia tools, resources i prompts. Protokół ma role host/client/server; w naszym systemie hostem jest backend runtime, klient łączy się z zatwierdzonym serwerem. [Źródło: MCP Architecture](https://modelcontextprotocol.io/specification/2025-06-18/architecture). Konkretna rewizja protokołu zostanie przypięta wraz z klientem; podany link opisuje wzorzec, nie nakazuje tej wersji produkcyjnej.

```text
ToolRegistry → NativeDomainTool → WooCommerce / Meta adapter
ToolRegistry → McpToolAdapter   → zatwierdzony serwer MCP
```

WooCommerce i Meta Ads w MVP mają natywne adaptery. MCP nie zastępuje domenowego API, policy engine, source-of-truth ani Temporal. Nazwa narzędzia z serwera nie oznacza zaufania do jego efektów.

**[P] Granica MCP:** allowlist serwerów i narzędzi, przypięte/zweryfikowane schematy, mapowanie nazw i wersji, limity czasu i odpowiedzi, ograniczone poświadczenia, autoryzacja każdego zasobu. Dynamiczne discovery nie publikuje narzędzi bezpośrednio modelowi. Zmiana kontraktu blokuje narzędzie do ponownego zatwierdzenia. Sesja i cache są izolowane według tenanta oraz principal; nie traktujemy samego ID sesji jako autoryzacji. Tokens nie trafiają do modelu ani do innego serwera.

Zewnętrzny prompt/resource traktujemy jako dane o niższym zaufaniu. MCP tool o efekcie zapisu pozostaje poza read loop nawet wtedy, gdy deklaruje się jako bezpieczny. Własny Action Executor nadal egzekwuje uprawnienie. Zyskiem MCP jest możliwość dodawania integracji bez zmiany kontraktu runtime, nie automatyczne poszerzanie autonomii.

## 8. Context Engineering, pamięć i hooks

### Kontekst [U, P]

| Warstwa | Gdzie przechowujemy | Co trafia do przebiegu |
|---|---|---|
| Business State | PostgreSQL | Dane danego wariantu, zamówień/agregatów, reklam i dostaw |
| Operational History | Decyzje, akcje, audyt | Ostatnie relewantne działania i konflikty |
| Procedural Knowledge | Registry skills i wersji | Wybrana procedura i potrzebne referencje |
| Historia bieżącego run | Pamięć przebiegu / zapis kontrolowany | Wiadomości narzędziowe potrzebne do zakończenia analizy |

Nie potrzebujemy wektorowej bazy do prostego pobrania zapasu po ID. Retrieval lub pamięć semantyczna mogą pojawić się przy dużym zasobie procedur albo korespondencji, po wykazaniu potrzeby. Wynik starej decyzji nie jest dowodem bieżącego stanu magazynu.

Kontekst ma wersję, `asOf`, źródła, zakres tenanta, flagi kompletności i referencje do dowodów. Kompresja kontekstu zachowuje twarde ograniczenia, dane jakości i powiązania dowodów. Opisy produktów i teksty kreacji są niezaufanymi danymi. Instrukcja w nazwie produktu nie może zmienić tool allowlist, polityki ani adresata powiadomienia. Do Inventory Guardian nie przekazujemy PII zamówień.

### Hooks [U, P]

| Hook | Kontrola | Zachowanie przy błędzie |
|---|---|---|
| `BeforeAgentRun` | Tenant, profil, snapshot, stan integracji | Stop run |
| `BeforeModelCall` | Deadline, koszt, rozmiar kontekstu, redakcja | Stop call |
| `AfterModelCall` | Usage, finish reason, parse, provider request ID | Odrzucenie niepełnego wyniku |
| `BeforeToolCall` | Schemat, allowlist, scope i uprawnienia | Deny tool |
| `AfterToolCall` | Schemat wyniku, rozmiar, dowody i audyt | Błąd narzędzia; wynik nie trafia jako poprawny |
| `BeforeActionExecution` | Bieżąca polityka, kill switch, stan i konflikt | Deny / expire / conflict |
| `AfterActionExecution` | Trwały rezultat, zlecenie weryfikacji | Stan niepewny i reconciliation |

Hooks bezpieczeństwa są deterministycznym kodem, nie poleceniami modelu. Błąd krytycznej kontroli zamyka ścieżkę wykonania. Awaria eksportu metryk nie musi blokować odczytu, ale brak możliwości trwałego zapisu zamiaru akcji blokuje mutację. Główne inwarianty egzekwujemy również bezpośrednio na granicy wykonawcy, aby inne wejście do backendu nie omijało hooka.

## 9. Decyzje, polityki i akcje

### Kanoniczny kontrakt [P]

`DecisionProposal` zawiera zaufaną kopertę i część analityczną. Model generuje tylko `recommendation`; backend wiąże ją z bieżącym uruchomieniem.

| Pole | Autor | Znaczenie |
|---|---|---|
| `schemaVersion`, `proposalId`, `revision` | Backend | Wersja i tożsamość niezmiennej propozycji |
| `opportunityId`, `agentRunId`, `contextSnapshotId` | Backend | Powiązanie ze zdarzeniem i dowodami |
| `createdAt`, `expiresAt` | Backend | Ważność wyznaczona polityką procesu |
| `recommendation.disposition` | Model | `ACT`, `NO_ACTION`, `ESCALATE`, `WAIT_FOR_DATA` |
| `recommendation.action` | Model | Typowana akcja lub `null` |
| `recommendation.evidenceIds` | Model, weryfikacja backendu | Referencje do istniejących dowodów tego run |
| `reasonCodes`, `summary`, `uncertainties` | Model, walidacja backendu | Uzasadnienie dla człowieka, bez chain-of-thought |

Rozróżniamy akcje `PAUSE_AD`, `REDUCE_ADSET_BUDGET`, `REDUCE_CAMPAIGN_BUDGET` i `NOTIFY_OPERATOR` oraz `action: null`. Budget actions są kontraktami rozszerzenia; domyślne MVP Inventory Guardian wykonuje tylko `PAUSE_AD`. Parametry budżetu wymagają wyznaczonego przez backend celu kwotowego, waluty i bazowej wersji, a nie ponawianego „odejmij 10%”.

Rozszerzenie dla Customer Support dodaje `SEND_SUPPORT_REPLY` i powiązanie z SupportCase według sekcji 14. Nie zmienia schematu `1.0`, listy akcji ani logiki referencyjnego Inventory Guardian. Nową wersję kontraktu dopuszczamy osobno dla profilu Support.

**Wariant E2E:** `PAUSE_AD` ma dokładnie `type`, `targetType: META_AD`, `targetId`, `parameters: {desiredStatus: PAUSED}`. Zestaw ID, uprawnienia, mapowanie, preconditions i wpływ ustala backend. `NOTIFY_OPERATOR` korzysta ze skonfigurowanego kanału, bez dowolnego adresu w propozycji. `ACT` wymaga akcji; pozostałe dyspozycje mają `action: null`. Powiadomienie o eskalacji może wykonać sam workflow w ramach uprzedniej konfiguracji.

**Walidacja:** JSON Schema, zamknięte enumeracje i pola, zgodność dyspozycji z akcją, istnienie dowodów, własność zasobu, zgodność target type, kompletność kontekstu, dozwolony typ operacji. LLM nie ustala `approved=true`, nie tworzy PolicyEvaluation i nie podpisuje upoważnienia. Samo `confidence=0.95`, jeśli kiedyś dodamy je jako metrykę, nie wystarczy do przyznania autonomii.

Tabela powyżej zachowuje kontrakt `1.0`, a Support używa rozszerzenia `1.1` z sekcji 14. Proponowany kontrakt `1.2` jawnie rozróżnia `RECOMMEND_ACTION`, `MONITOR`, `NO_ACTION`, `INSUFFICIENT_DATA` i `ESCALATE`; reguły zgodności, mapowanie legacy i plan obserwacji mają źródło w [05 — pipeline i kontrakty](05-DECISION-INTELLIGENCE-ARCHITECTURE.md#e-decision-making-pipeline-i-kontrakty). Nie dopisujemy nowych wartości do niezmiennego schematu `1.0`. MONITOR nie może zostać po cichu zmapowany na NO_ACTION; wdrożenie wymaga obsługi przez konsumentów i workflow.

### Policy Engine

Wyniki: `ALLOW`, `REQUIRE_APPROVAL`, `DENY`, `DEFER`. `DEFER` oznacza brak warunków do rozstrzygnięcia, np. oczekiwanie na świeży odczyt. PolicyEvaluation zapisuje wersję polityki, fakty wejściowe, reason codes, moment oceny i zakres dopuszczenia.

Kolejność: izolacja i capabilities → kill switch i integracja → ważność i świeżość → zgodność celu/mapowania → konflikt i historia → autonomia/limity → zgoda. Naruszeń bezpieczeństwa nie można „przeklikać” akceptacją. Zgoda dotyczy hasha rewizji, celu i parametrów, ma autora i termin. Wznowienie po akceptacji wymaga ponownej oceny aktualnej polityki i stanu.

Dla pauzy limit procentowy budżetu nie ma sensu: pauza ma własny limit liczby zasobów, zakresu mapowania i wpływu operacyjnego. Limity redukcji budżetu liczymy kumulatywnie w oknie względem zdefiniowanej bazy. Nie pozwalamy omijać limitu przez wiele małych akcji albo różne workflow.

### AuthorizedAction i wykonanie

Zaufany serwis wystawia krótko ważne upoważnienie powiązane z `proposalId/revision`, tenantem, integracją, akcją, celem, hashem parametrów, wersją polityki, wymaganiami stanu, zgodą i kluczem idempotencji. W monolicie wystarczy niepodrabialna referencja do trwałego rekordu i kontrolowany konstruktor; przy rozdzieleniu usług potrzebny jest odpowiednio zabezpieczony kontrakt. JSON o takich polach od modelu nie jest upoważnieniem.

Executor pod blokadą zasobu sprawdza upoważnienie, bieżące warunki i rejestr akcji, zapisuje zamiar, wywołuje adapter i zapisuje wynik. Upoważnienie nie staje się nieważne dla bezpiecznej rekoncyliacji tej samej akcji tylko dlatego, że jego pierwsze użycie nastąpiło przed awarią; nowa mutacja po wygaśnięciu wymaga ponownej autoryzacji. Nie wykonujemy najpierw API, a dopiero później próbujemy odtworzyć, dlaczego wolno było je wywołać.

### Stany i przejścia

| Obiekt | Ścieżka / odgałęzienia |
|---|---|
| Opportunity | `DETECTED → ANALYZED → PROPOSED`; `WAITING_DATA → ANALYZED` po odświeżeniu |
| Ocena | `PROPOSED → APPROVED` przy ALLOW; `WAITING_APPROVAL → APPROVED` po zgodzie i recheck |
| Brak wykonania | `NO_ACTION`, `REJECTED`, `EXPIRED`, `CANCELLED` z przyczyną i możliwością nowej analizy |
| Wykonanie | `APPROVED → EXECUTING → VERIFIED → CLOSED`; w razie problemu `CONFLICT`, `FAILED`, `UNKNOWN` |
| ActionExecution | `PLANNED → IN_PROGRESS → APPLIED → VERIFIED`; alternatywnie `NOOP`, `UNKNOWN`, `FAILED`, `CONFLICT` |

`APPLIED` oznacza przyjęcie żądania lub zaobserwowany skutek, `VERIFIED` — potwierdzenie wymaganych warunków. `NOOP` może zamknąć problem, jeżeli żądany stan istniał wcześniej, lecz nie przypisujemy zmiany operatorowi. `UNKNOWN` wymaga reconciliation. Zamknięta Opportunity zachowuje wynik; kolejny epizod dostaje nowy identyfikator. Pełna tabela dozwolonych przejść będzie kontraktem implementacji, z testem odrzucania przejść nielegalnych.

## 10. Temporal i semantyka awarii [U, P]

Workflow odpowiada za kolejność Activities, timery, oczekiwanie na approval, wznowienie i zakończenie. HTTP do modeli i integracji, zapisy w bazie i odczyty bieżącej polityki wykonują Activities. Workflow operuje na zapisanych wynikach; nie pobiera sam zegara systemowego, losowości ani konfiguracji ze Springa przy każdym replay.

`AgentActivity` jest krótkotrwałym, limitowanym zadaniem. Retry może ponownie wywołać model, jeśli wynik nie został trwale zapisany. Dlatego zlecenie analizy ma stabilny klucz i osobny zapis rezultatu; ponowne wykonanie najpierw sprawdza istniejący rezultat. Pozostaje możliwość podwójnego kosztu modelu przy awarii w wąskim oknie po odpowiedzi i przed zapisem. Nie oznacza to podwójnej akcji biznesowej.

Polityki retry są osobne dla odczytu, analizy, wykonania i weryfikacji. Zmiana statusu z niejednoznacznym wynikiem uruchamia odczyt, nie ślepy POST. Temporal rekomenduje idempotentne Activities, bo kod może wykonać się ponownie. [Źródło: Activity Definition](https://docs.temporal.io/activity-definition).

**[P] Approval:** zaufane API sprawdza użytkownika, tenant i rewizję, zapisuje zgodę i przekazuje sygnał do workflow. Powtórki sygnału są deduplikowane po approval ID. Workflow ma termin; późna zgoda do starej rewizji nie uruchamia akcji. Nowy sygnał zapasu podczas oczekiwania może unieważnić propozycję. Sposób sygnalizowania i testy pokazuje [Inventory Guardian](03-INVENTORY-GUARDIAN-E2E.md).

**[P] Konflikty:** serializacja według `tenant + integration + targetType + targetId` oraz kontrola zasobów nadrzędnych przy akcjach adset/campaign. Blokada nie obejmuje zewnętrznego użytkownika Meta. Gdy API nie udostępnia warunkowego zapisu, pozostaje luka między odczytem i mutacją; ograniczamy ją krótkim czasem, małym zakresem i recheckiem, a konflikt po zapisie eskalujemy. Nie deklarujemy atomowości z Meta.

## 11. Delegacja i dalszy rozwój [U, P]

MVP: jeden runtime, kilka definicji profili, bez obowiązkowego multi-agent frameworka. Gdy ewaluacja pokaże wartość, centralny orchestrator może wywołać Business Analyst jako subagenta. Dostaje on tylko podzadanie i potrzebny snapshot, zwraca typowany wynik z dowodami, podlega wspólnemu budżetowi i deadline.

Delegacja ma maksymalną głębokość i allowlist dozwolonych relacji; nie może tworzyć nieograniczonej rekursji. Subagent nie dostaje prawa do mutacji ani do dalszego rozszerzania uprawnień. Wynik subagenta jest materiałem do walidacji, a nie dowodem źródłowym. Rozbieżne oceny uruchamiają eskalację lub dodatkowy odczyt, nie głosowanie przyznające uprawnienia.

## 12. Obserwowalność, ewaluacja i kryteria odbioru [P]

Jedna ścieżka korelacji: `tenantId → opportunityId → workflowId → agentRunId → proposalId/revision → actionId`. Rejestrujemy snapshot i jego hash, wersje profilu/skill/tools/prompts/polityki, model i parametry, wywołania narzędzi, zwięzłe uzasadnienie, dowody, usage, zgodę i wynik weryfikacji. Nie wymagamy prywatnego chain-of-thought modelu. Replay historycznego wejścia może dać inny tekst modelu; odtwarzalność warunków i dowodów nie oznacza identycznej odpowiedzi.

Prompty i odpowiedzi podlegają retencji i redakcji. Sekrety nie są logowane. Duże snapshoty można przechować poza historią Temporal, a do workflow przekazać referencję i hash. Odczyt takiej referencji również wymaga scope tenanta.

| Poziom testów | Co sprawdzamy |
|---|---|
| Jednostkowe | Forecast, świeżość, mapowania, polityki, walidatory i przejścia stanów |
| Kontraktowe | Gateway, capabilities, tools, schemat propozycji, adaptery API |
| Izolacja i nadużycia | Obcy tenant ID, fałszywe evidence, prompt injection, nieznany tool, sfałszowane upoważnienie |
| Ewaluacja AI | Stałe przypadki biznesowe dla każdej pary skill/model; poprawność decyzji i dowodów |
| Workflow | Replay, timery, sygnały, timeout, crash/retry i niejednoznaczny skutek |
| Shadow / pilot | Odsetek akceptacji, fałszywe pauzy, koszt, czas reakcji i kompletność audytu |

**[Z] Bramka do L2:** zero wykonanych nieautoryzowanych akcji w testach; wszystkie przypadki niepewnego mapowania z zestawu regresyjnego eskalują; każda mutacja ma pełny audyt; koszt i jakość mieszczą się w uzgodnionych celach pilota. Progi jakości diagnostycznej ustalamy na danych sklepów i z jawnym mianownikiem. Wynik zero błędów na skończonym zbiorze nie stanowi gwarancji przyszłego działania.

## 13. Rozstrzygnięcia przed implementacją [O]

1. Wybrać stabilne, zgodne wersje Java/Spring Boot/Spring AI/Temporal SDK i potwierdzić brak automatycznej drugiej pętli tools.
2. Wybrać modele według danych testowych, capabilities, budżetu oraz dopuszczonych regionów przetwarzania.
3. Zatwierdzić schemat `DecisionProposal v1`, manifest skills i reguły ewolucji wersji.
4. Ustalić retencję snapshotów, audytu i treści promptów, role ich odczytu oraz miejsce przechowywania.
5. Potwierdzić semantykę zapasu, mapowania i warunki pauzy z dokumentu E2E przed aktywacją L2.

Zakres produktowy i wspólny rejestr decyzji: [01 — Produkt](01-PRODUCT-VISION-AND-ARCHITECTURE.md). Referencyjna implementacja kontraktów: [03 — Inventory Guardian](03-INVENTORY-GUARDIAN-E2E.md).

## 14. Customer Support Agent [D, P]

**D-07:** Obsługa e-mail jest częścią MVP. Poniższe kontrakty są propozycją implementacji w istniejącym runtime, Policy Engine, Action Executor i Temporal. Interakcje operatora i endpointy mają jedno miejsce opisu: [Customer Support UI](04-UI-UX-SPECIFICATION.md#6-customer-support--conversation-detail).

### Model domeny i odpowiedzialności [P]

| Obiekt | Zakres i relacje |
|---|---|
| `Conversation` | Wątek skrzynki sklepu, uczestnicy, kolejność wiadomości, wersja i tryb własności |
| `Message` | Wiadomość przychodząca/wychodząca, pochodzenie AI/człowiek/system, provider ID i status wysyłki |
| `SupportCase` | Cel obsługi w konwersacji, klasyfikacja, priorytet, aktualny kontekst i wynik |
| `CustomerContext` | Zweryfikowane powiązania klient–zamówienia i zakres danych do ujawnienia |
| `Escalation` | Przyczyna, pilność, podsumowanie, brakujące dane, szkic i właściciel obsługi |
| `ResponseDraft` | Wersjonowana treść odpowiedzi, powiązane fakty, trigger message i hash treści |
| `HumanAssignment` | Operator, czas przejęcia, wersja własności i jawne oddanie sprawy agentowi |
| `SupportPolicy` | Dozwolone kategorie L2, źródła informacji, limity, tożsamość i reguły eskalacji |

Conversation ma stan roboczy `NEW`, `AI_HANDLING`, `NEEDS_HUMAN`, `HUMAN_OWNED`, `RESOLVED`, niezależny od statusu wysyłki Message. Nowa wiadomość może ponownie otworzyć rozwiązaną sprawę. Nie tworzymy Opportunity dla każdego maila; SupportCase jest korzeniem procesu. Jeśli rozmowa wymaga odrębnej decyzji biznesowej, tworzymy i linkujemy Opportunity, zachowując wiadomości w Support.

**Rozszerzenie kontraktu:** `DecisionProposal schemaVersion=1.1` dla profilu Support dodaje zaufane `supportCaseId`, `conversationId`, `triggerMessageId`, `conversationVersion`, `ownershipVersion`; `opportunityId` może być `null`. Pozostała koperta i dyspozycje z sekcji 9 zachowują znaczenie. `ACT` z `SEND_SUPPORT_REPLY` wskazuje `targetType=SUPPORT_CONVERSATION` i cel zgodny z kontekstem. W parametrach model proponuje `bodyText` oraz referencje do faktów; backend tworzy niezmienną rewizję ResponseDraft i wiąże jej ID/hash z propozycją przed oceną i wysyłką. Model nie podaje dowolnych odbiorców, adresu nadawcy, CC/BCC ani nagłówków.

`ESCALATE` ma `action: null`, podsumowanie i reason codes; workflow zapisuje Escalation i ResponseDraft jako wewnętrzny wynik. Szkic przy eskalacji nie jest automatycznie dopuszczony do wysłania. Edycja przez człowieka tworzy nową wersję draftu i ocenę polityki przed wysyłką; wysyłka ręczna nadal korzysta ze wspólnego executor/ledger, z principal operatora i bez wymogu uruchamiania LLM.

### Skills i read tools [P]

| Skill | Wynik |
|---|---|
| `classify-customer-request` | Kategoria, pilność, język i wymagane źródła |
| `answer-order-status` | Odpowiedź oparta na zweryfikowanym powiązaniu zamówienia |
| `answer-product-question` | Odpowiedź z katalogu i zatwierdzonej wiedzy sklepu |
| `draft-customer-response` | Szkic z referencjami faktów, bez samodzielnej wysyłki |
| `prepare-support-handoff` | Podsumowanie problemu, wykonane sprawdzenia, powód eskalacji i proponowana odpowiedź |

| Tool READ | Wejście modelu | Egzekwowana granica |
|---|---|---|
| `get_conversation_history` | `conversationId`, ograniczona liczba wiadomości | Tylko bieżąca sprawa i niezbędna historia |
| `get_order_status` | `orderId` z dopuszczonego kontekstu | Zweryfikowane powiązanie klient–zamówienie i świeżość |
| `get_order_details` | `orderId`, zakres pól | Minimalne pola; bez danych płatniczych i nadmiarowego PII |
| `get_product_details` | `productVariantId` | Fakty katalogowe z wersją i czasem odczytu |
| `get_store_policy` | Typ polityki, język | Wyłącznie opublikowana wersja reguł sklepu |

Lokalne `get_product_details` może korzystać z istniejącego Commerce Service; nie powielamy integracji Woo. Załączniki i treść wiadomości to niezaufane dane. Runtime nie wykonuje linków, instrukcji ani kodu z maila. Brak źródła statusu przesyłki oznacza brak możliwości podania terminu dostarczenia; status zamówienia nie zastępuje śledzenia przewoźnika.

### Polityki odpowiedzi i human handoff [P]

| Kategoria | Zachowanie MVP |
|---|---|
| Status zamówienia | L2 po weryfikacji relacji, aktualnych danych i dozwolonych pól; inaczej eskalacja |
| Fakty o produkcie i zasady sklepu | L2 na zatwierdzonych źródłach, bez wymyślania obietnic i uprawnień |
| Reklamacja, nietypowa sprawa, sprzeczne dane | NEEDS_HUMAN, podsumowanie i szkic |
| Zmiana adresu, anulowanie, zwrot | Przygotowanie sprawy i eskalacja; brak tych mutacji w MVP Support |
| Niepewna tożsamość lub żądanie cudzego zamówienia | Bez ujawnienia danych; eskalacja lub zatwierdzony neutralny komunikat |
| Sprawa przejęta przez człowieka | Brak automatycznej odpowiedzi; AI może przygotować szkic na żądanie |

SupportPolicy sprawdza własność konwersacji, wersję triggera, stan integracji, weryfikację odbiorcy, źródła faktów, ograniczenia treści, język, limit odpowiedzi i kill switch. Sam wysoki confidence modelu nie nadaje L2. Detektor automatycznych maili, bounce i autoresponderów oraz limit odpowiedzi na konwersację zapobiegają pętlom. Adresata ustala backend z dopuszczonej relacji skrzynka–wątek–klient; nie ufa dowolnemu Reply-To lub numerowi zamówienia w treści.

Operator może przejąć sprawę, edytować szkic, odpowiedzieć, rozwiązać ją lub jawnie oddać AI. Takeover atomowo zwiększa `ownershipVersion`, blokuje nowe automatyczne wysyłki i unieważnia oczekujące propozycje. Executor ponownie sprawdza wersję bezpośrednio przed dispatch. Nie da się cofnąć wiadomości już przyjętej przez dostawcę; UI pokazuje wysyłkę w toku i potwierdza przejęcie z informacją o tej granicy. Nowy mail klienta unieważnia nieaktualny szkic automatycznej odpowiedzi.

### Workflow i niezawodność e-mail [P]

`IncomingMessage → Classification → Context → Skill → DecisionProposal → PolicyEvaluation → SendReply albo HumanHandoff → Verification → Audit`.

Adapter odbioru deduplikuje provider message ID w zakresie skrzynki; Message-ID jest pomocniczy i nie daje globalnej autoryzacji. Wątek buduje z provider thread ID oraz zweryfikowanych nagłówków, bez mieszania tenantów. Zdarzenie przychodzące i outbox są trwałe. Jeden aktywny workflow na SupportCase serializuje obsługę kolejnych wiadomości; nowy trigger superseduje oczekującą odpowiedź przed dispatch. Zapytania do LLM, bazy i poczty są Activities.

Wysyłka ma klucz `tenant/mailbox/conversation/triggerMessage/draftRevision`, trwały ledger i serializację AI/człowiek. Stan: `DRAFT → QUEUED → SENDING → ACCEPTED`; kolejne potwierdzenia dają `DELIVERED`, `BOUNCED` albo `FAILED`, jeśli dostawca udostępnia te dane. Timeout po dispatch daje `UNKNOWN`; bezpieczny odczyt Sent/provider ID poprzedza ponowienie. Jeśli dostawca nie umożliwia rozstrzygnięcia, blokujemy automatyczne resend i eskalujemy. Stabilny Message-ID sam nie gwarantuje exactly-once u odbiorcy.

`ACCEPTED` potwierdza przyjęcie przez dostawcę, a nie odczyt przez klienta ani dostarczenie do inbox. `DELIVERED` pokazujemy wyłącznie z dostępnym dowodem dostawcy i opisem jego znaczenia. Bounce aktualizuje wynik i otwiera interwencję; nie uruchamia dialogu z autoresponderem. Nie obiecujemy dokładnie jednego zewnętrznego skutku bez odpowiedniego kontraktu dostawcy. Reguły Temporal z sekcji 10 pozostają wspólne.

### Audyt, dane i testy [P, O]

Audyt wiąże SupportCase, conversation/message, run, draft revision, proposal, policy evaluation, principal, takeover, provider message ID i wynik. Zapisy treści i danych klienta mają odrębną retencję i dostęp od zwięzłej historii operacyjnej. **O-11–O-13:** dostawca skrzynki, sposób weryfikacji tożsamości, role, czasy eskalacji oraz zakres/retencja załączników wymagają rozstrzygnięcia; do tego czasu brak założenia o automatycznej analizie wszystkich załączników.

Przed L2 testujemy: obcy order ID i spoofing nadawcy, prompt injection, fikcyjne terminy dostawy, brak źródła, zduplikowany inbound, nową wiadomość w czasie generowania szkicu, takeover tuż przed dispatch, równoległy send człowieka i AI, utratę potwierdzenia wysyłki, bounce/autoresponder, wygaśnięcie tokenu i zmianę polityki. Poprawna odpowiedź tekstowa nie zastępuje testu izolacji i wysyłki.

## 15. Reprezentacje decyzji dla UI [D, P]

D-06 wymaga trzech warstw informacji z jednego zestawu dowodów: `Business Summary`, `Decision Explanation`, `Analytical Evidence`. Summary korzysta z `summary` i statusów backendu; Explanation łączy reason codes, niepewności, zakres akcji i wynik polityki; Evidence pobiera liczby ze snapshotu i serwisów analitycznych. Nie generujemy trzech niezależnych wersji faktów przez LLM. Prezentujemy uzasadnienie użytkowe, bez chain-of-thought.

Warstwa odczytu zwraca wersję propozycji, snapshot ID, evidence refs, definicje metryk i contextual analysis links. Dane historyczne użyte w decyzji są odróżnione od aktualnej analityki. Nawigacja zachowuje daty, sklep, produkt i kampanię; szczegółowy kontrakt prezentacji znajduje się w [UI/UX](04-UI-UX-SPECIFICATION.md#3-wspólne-kontrakty-frontendbackend-p).

## Zmiany w wersji 1.1

Dodano Customer Support Agent, pięć Skills i pięć read tools, rozszerzenie kontraktu decyzji oraz polityki odpowiedzi i przejęcia przez człowieka. Opisano trwały workflow e-mail, niejednoznaczną wysyłkę i audyt. Dodano reprezentacje danych dla progressive disclosure; dotychczasowy runtime i Inventory Guardian pozostają bez zmian logiki.

## 16. Decision Intelligence w istniejącym runtime [D, P]

Knowledge Registry obejmuje dotychczasowy SkillRegistry i wersjonuje również Playbooks oraz referencje eksperckie. SkillLoader nadal ładuje wybraną procedurę; Playbook wiąże procedury i scenariusze analizy, a Tools dostarczają dane. Deterministyczne reguły/metryki są kodem aplikacji; Skills ich nie nadpisują. Polityki i workflow zachowują dotychczasowe granice wykonania.

Knowledge & Context Builder jest rozwinięciem obecnego Context Builder: łączy właściwe wersje Domain Knowledge, Store Business Profile, Operational Data i Decision Experience. Store Profile nadaje cel i preferencje, Metrics & Evidence Engine dostarcza obliczone KPI, jakość i lineage, Decision History udostępnia historię, a Outcome Evaluator zasila ją obserwacjami z ograniczeniami interpretacji. Historia nie jest dowodem przyczynowym i nie zmienia samodzielnie strategii. Offline Evaluation Framework sprawdza nowe wersje Skills, definicji i modeli przed aktywacją.

Kontekst i dowody są niezależne od LLM; dotychczasowy ModelGateway adaptuje je do możliwości konkretnego modelu. Run pinuje również profil sklepu, knowledge release, definicje metryk i manifest kontekstu. Obliczenia finansowe wykonuje backend, a każda rekomendacja ma zweryfikowane evidence refs. Nowe odczyty wiedzy/profilu/historii podlegają istniejącemu scope tenanta i ToolDispatcher; nie publikują całej bazy modelowi. Pełne odpowiedzialności, kontrakty Java/JSON i ścieżka walidacji: [05](05-DECISION-INTELLIGENCE-ARCHITECTURE.md).

## Zmiany w wersji 1.2

Rozszerzono istniejące Registry i Context Builder o Decision Intelligence, wskazano relacje z metrykami, profilem, historią i ewaluacją. Dodano odsyłacz do proponowanej ewolucji kontraktu decyzji, bez przepisywania runtime, gateway, Temporal i Policy Engine.
