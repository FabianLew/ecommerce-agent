# Autonomous E-commerce Operator — Decision Intelligence Architecture

Wersja: 1.0 · Data: 14 listopada 2026 · Status: szczegółowa specyfikacja do przeglądu

Powiązania: [01 — produkt i zakres MVP](01-PRODUCT-VISION-AND-ARCHITECTURE.md), [02 — Agent Runtime i wykonanie](02-AI-AGENT-ARCHITECTURE.md), [03 — Inventory Guardian E2E](03-INVENTORY-GUARDIAN-E2E.md), [04 — UI/UX](04-UI-UX-SPECIFICATION.md).

Obowiązuje wspólna legenda **[D] decyzja / [U] uzgodniony kierunek / [P] propozycja / [Z] założenie / [O] pytanie otwarte**. D-08/D-09 zatwierdzają cztery źródła wiedzy i zakres Decision Intelligence w MVP. Szczegóły struktur, kontrakt `1.2`, nazwy pakietów i procedury tego dokumentu to **[P]**, a liczby przykładów to **[Z]**. Nie opisujemy istniejącej implementacji ani zweryfikowanej skuteczności strategii marketingowej.

Zakres dokumentu: skąd pochodzi wiedza biznesowa, jak budować podstawy decyzji i jak oceniać wyniki. Własność pętli agenta, Spring AI, MCP, hooks, autoryzacja, idempotencja i Temporal zachowują specyfikację z dokumentu 02; nie są definiowane ponownie.

## A. Architektura wiedzy

### A1. Cztery źródła [D]

| Źródło | Co wnosi | Prawda operacyjna i przechowywanie | Główne ograniczenie |
|---|---|---|---|
| Domain Knowledge | Jak analizować sytuację: Skills, Playbooks, referencje eksperckie | Wersjonowane pliki i metadata Knowledge Registry | Instrukcja nie nadaje uprawnień ani nie dowodzi trafności reguły w każdym sklepie |
| Store Knowledge | Co jest ważne: cele, ekonomia, preferencje, zatwierdzona strategia | Store Business Profile w PostgreSQL i referencje do kosztów/polityk | Cel GROWTH nie uchyla limitów wydatku i ryzyka |
| Operational Data | Co wiadomo o sklepie i reklamach w danym czasie | Znormalizowane obserwacje, Metrics & Evidence Engine, snapshoty | Raportowane, estymowane, niepełne i opóźnione dane mają różne ograniczenia |
| Decision Experience | Co zaproponowano, wykonano i zaobserwowano | Decision History, ActionExecution, Outcome i oceny ekspertów | Korelacja w historii nie jest przyczynowym dowodem skuteczności |

Wiedza bazowa modelu jest zdolnością interpretacji i języka, a nie piątym autorytatywnym źródłem faktów o sklepie. Gdy wymagany fakt nie występuje w czterech źródłach, agent wskazuje brak. Nie uzupełnia nieznanego COGS „typową marżą branżową”.

```mermaid
flowchart TD
  DK[Domain Knowledge / Registry] --> KC[Knowledge & Context Builder]
  SP[Store Business Profile] --> KC
  OD[Operational Data] --> ME[Metrics & Evidence Engine]
  ME --> KC
  DH[Decision History / Outcome] --> KC
  KC --> RT[Istniejący Bounded Agent Runtime]
  RT --> DV[Proposal / Evidence Validation]
  DV --> PE[Istniejące Policy / Approval / Execution]
  PE --> OE[Outcome Evaluator]
  OE --> DH
  EV[Offline Evaluation / review] --> Release[Zatwierdzony release wiedzy]
  Release --> DK
```

Strzałka Outcome → History nie aktualizuje sama Registry, polityki ani strategii. Release wymaga kontrolowanego review. Wszystkie komponenty działają w modularnym monolicie Spring Boot; timery i procesy trwałe obsługuje dotychczasowy Temporal.

### A2. Registry, Skill, Playbook i reguła [P]

Knowledge Registry rozszerza SkillRegistry, zamiast tworzyć równoległy katalog. Zapewnia rozwiązywanie ID/wersji, hash, status wydania, scope, compatibility i pochodzenie. Przykładowa organizacja:

```text
intelligence/knowledge/
  marketing-pack/
    release.yaml
    skills/
      campaign-diagnosis/SKILL.md
      budget-optimization/SKILL.md
      creative-fatigue-analysis/SKILL.md
      profitability-analysis/SKILL.md
      campaign-learning-analysis/SKILL.md
      scaling-readiness/SKILL.md
    playbooks/
      performance-deterioration.md
      profitable-growth.md
      insufficient-conversions.md
    references/
      source-manifest.yaml
      attribution-and-reporting.md
    evals/
      cases.jsonl
      expert-rubric.yaml
```

| Artefakt | Odpowiedzialność | Przykład granicy |
|---|---|---|
| Skill | Procedura jednej kompetencji, dane wymagane, kryteria abstencji i wynik | Diagnozuje zmęczenie kreacji; nie publikuje nowej reklamy |
| Playbook | Łączy scenariusze, kolejność kompetencji i warianty odpowiedzi | Spadek wyników → jakość danych → diagnoza → ewentualna rekomendacja |
| Deterministyczna reguła | Obliczenie lub walidacja w kodzie z wersją | CPA, kompletność okresu, budżet, zgodność evidence |
| Policy | Egzekwowalna granica dopuszczalności | Zakaz wzrostu wydatku w MVP, wymagane approval i scope |
| Temporal Workflow | Trwała realizacja etapów i oczekiwanie | Zaplanuj ponowną analizę, zachowaj deadline |

Playbook nie jest dowolnym wykonywalnym workflow ani ukrytym skryptem. Jego reguły wyboru etapów są mapowane na zatwierdzone możliwości aplikacji. Nawet jeśli Skill rekomenduje poprawę budżetu, wykonanie zależy od istniejącego katalogu akcji i polityk; sam Marketing Pack nie rozszerza MVP o autonomiczne zwiększanie budżetów.

### A3. Provenance, wiarygodność i aktualizacje [P]

Każdy wpis wiedzy ma: `knowledgeId`, typ, wersję, content hash, autora/właściciela, źródło i URL lub identyfikator materiału, `publishedAt`, `retrievedAt`, `reviewedAt`, `reviewDueAt`, zakres platformy/API, założenia, applicability, review status oraz zatwierdzający release. Własny materiał eksperta ma autora, datę i zakres obserwacji; brak publicznego URL nie czyni go pozbawionym pochodzenia.

Poziomy źródeł **[P]**: `OFFICIAL_PLATFORM`, `EXPERT_REVIEWED`, `STORE_APPROVED`, `OBSERVATIONAL`, `UNVERIFIED`. To klasy pochodzenia, nie ranking automatycznie rozstrzygający każdą sprawę. Dla własności API podstawą jest zgodna wersja dokumentacji platformy; dla celu biznesowego — zatwierdzony profil sklepu; dla bieżącego zapasu — źródło operacyjne. Oficjalny poradnik marketingowy nie dowodzi rentowności sklepu. Niezweryfikowany materiał nie steruje samodzielnie akcją.

Zatwierdzony release wiąże wersje Skills, Playbooks, referencji, definicji metryk i minimalnych capabilities. Zmiana materialna wymaga nowej wersji i testów. Nowa wersja dokumentacji zewnętrznej nie podmienia automatycznie używanej procedury. Przeterminowany review lub wycofany materiał wywołuje kontrolę applicability; krytyczna niezgodność blokuje nową analizę do review, a nie usuwa historii.

Lifecycle: autor/ekspert przygotowuje zmianę → techniczny i biznesowy review → testy offline → shadow → zatwierdzenie → aktywacja dla wybranego zakresu → monitoring → ewentualny rollback. Trwający run pinuje release. Krytyczne wycofanie bezpieczeństwa może zatrzymać przyszłą mutację mimo wcześniejszego przypięcia wiedzy. Model nie ma toola zapisu do Registry i nie przyznaje statusu `APPROVED` własnemu wynikowi.

Globalna wiedza jest współdzielona bez danych klientów; profile i doświadczenie sklepu mają scope tenant/store. Tenant overrides są jawnymi wersjami z review, nie dopisanym tekstem o wyższym priorytecie niż system/policy. Cache oraz retrieval filtrują scope przed doborem wyników. Przypadki z innych sklepów wymagają uprzedniego dopuszczenia i anonimizacji; MVP nie zakłada współdzielenia ich historii.

## B. Store Business Profile

### B1. Model i cele [D, P]

StoreBusinessProfile przechowuje intencję sklepu i referencje do ekonomii; nie zastępuje policy engine, katalogu kosztów ani integracji. Wersja jest niezmienna po zatwierdzeniu. Model danych:

| Grupa | Pola / semantyka |
|---|---|
| Tożsamość | Profile ID, tenant, store, wersja, status DRAFT/APPROVED/SUPERSEDED, effectiveFrom, approvedBy |
| Cele | `PROFITABILITY`, `GROWTH`, `BALANCED`; priorytety i horyzont, opis preferowanego trade-off |
| Ekonomia | Waluta i tax basis, ref do wersjonowanych COGS per SKU, fulfillment, fees, zwrotów i innych kosztów |
| Budżet | Zadeklarowany plan wydatków z okresem, scope kont/kanałów, ref do twardych polityk; osobno observed budgets Meta |
| Ryzyko | LOW/MEDIUM/HIGH jako preferencja, dopuszczona strata z walutą/horyzontem, minimalna marża jako cel; wartości mogą być nieznane |
| Strategia | Np. cash preservation, ciągłość zapasu, pozyskanie klientów, sezonowe priorytety; daty i applicability |
| Jakość | Missing fields, konflikty, provenance każdego istotnego pola, confidence źródła i potwierdzenia operatora |

PROFITABILITY priorytetyzuje znany wkład do marży i ryzyko straty; GROWTH pozwala rozważyć wzrost w dopuszczonych granicach; BALANCED wymaga uzgodnionego kompromisu. To kierunek rankingowania rekomendacji, nie z góry wyznaczony mnożnik budżetu. Żaden cel nie upoważnia do zwiększenia wydatków. **O-19:** jak sklep definiuje oczekiwany horyzont i kompromis, zanim włączymy rekomendacje zależne od tych danych.

Przykład **[Z]**, zatwierdzone dane i jawne braki:

```json
{
  "profileId": "store-profile-01",
  "tenantId": "tenant-dtc-01",
  "storeId": "store-01",
  "version": 3,
  "status": "APPROVED",
  "effectiveFrom": "2026-11-14T07:00:00Z",
  "goal": "BALANCED",
  "planningHorizonDays": 30,
  "riskPreference": "LOW",
  "currency": "PLN",
  "taxBasis": "EX_TAX",
  "economicModelRef": "economics-01@5",
  "plannedAdSpend": {
    "amountMinor": 3000000,
    "currency": "PLN",
    "period": "2026-11",
    "scope": "META_ACCOUNT:act-700"
  },
  "policyRefs": ["inventory-protection@1.0.0", "marketing-controls@2"],
  "priorities": ["INVENTORY_CONTINUITY", "CONTRIBUTION_MARGIN"],
  "missingFields": ["MAX_ACCEPTABLE_ACQUISITION_LOSS"],
  "fieldProvenance": {
    "goal": {"sourceType": "STORE_APPROVED", "approvedBy": "user-owner-01"},
    "economicModelRef": {"sourceType": "STORE_APPROVED", "sourceRef": "financial-inputs@5"}
  }
}
```

30 000 PLN jest przykładem planu, a nie ogólnym limitem produktu. Brak tolerancji straty blokuje tylko analizy wymagające takiego założenia. Wybrane pole goal może być nieustawione w szkicu; nie podajemy modelowi niezatwierdzonego defaultu jako decyzji właściciela.

### B2. Automatyczne pozyskiwanie, konflikty i braki [P]

Integracje dostarczają obserwacje: przychody, ceny, rabaty, zamówienia, budżety reklamowe, zapas i udostępnione koszty. Nie wszystkie sklepy Woo mają dostępny COGS; integracja nie dowodzi jego istnienia. Z danych możemy przygotować sugestię kosztów i celu, lecz cel/progi strat zatwierdza uprawniony użytkownik. Budget w Meta nie jest równoznaczny z całkowitym planem miesięcznym sklepu.

Pole może mieć wartość zatwierdzoną, obserwowane kandydatury i konflikt. Precedence definiujemy per dziedzina: np. uzgodniony WMS dla zapasu, zatwierdzony katalog kosztowy dla COGS, bieżący Meta dla konfiguracji reklam. Manual override ma autora, uzasadnienie, datę obowiązywania i review/expiry. Nie wybieramy po cichu najnowszej lub najbardziej korzystnej marży.

Zmiana źródłowego COGS tworzy rewizję economic model i propozycję przeliczenia; zachowujemy historię okresów obowiązywania. Istotny konflikt blokuje automatyczną decyzję zależną od tego pola, a nie wszystkie procesy sklepu. Profil wskazujący politykę sprzeczną z aktualnym Policy Engine nie uchyla jej. Każdy run otrzymuje konkretną wersję profilu; istotna zmiana między analizą a mutacją wymaga ponownej walidacji/reanalizy.

## C. Metrics & Evidence Engine

### C1. Kontrakty i definicje [D, P]

Engine jest deterministycznym rozwinięciem obecnych agregacji Commerce/Reporting i rejestru Evidence. Wersjonuje definicje, liczy KPI, waliduje okresy i mianowniki, nadaje flagi jakości oraz zachowuje lineage. LLM może poprosić o metrykę lub wyjaśnić wynik; nie dostarcza autorytatywnej liczby obliczonej we własnym tekście.

| Metryka | Definicja / wymagane ograniczenie |
|---|---|
| Net Sales | Kwalifikująca się sprzedaż bez podatku, po rabatach i zwrotach przychodowych; jawne włączenie/wyłączenie opłat za dostawę |
| Orders / AOV | Liczba kwalifikujących się zamówień; `AOV = NetSales / Orders` na tej samej bazie okresu i scope |
| Refunds / return rate | Kwota refundacji i udział zwrotów w zdefiniowanej kohorcie; refundacja nie dowodzi powrotu zapasu |
| COGS | Koszt jednostkowy obowiązujący dla sprzedanej jednostki; zwroty/recovery według jawnej metody, nie dzisiejszy koszt wszystkich historycznych zamówień |
| Contribution profit | NetSales minus COGS, fulfillment, fees, reklamy i inne koszty zmienne; wzór C2 |
| Spend | Koszt raportowany przez reklamodawcę w walucie i scope konta; dojrzałość rozliczeń jest osobną flagą |
| Impressions / clicks | Liczniki z jawnym rodzajem kliknięcia; link clicks nie są zamiennie all clicks |
| CPM / CPC / CTR | `1000*Spend/Impressions`; `Spend/Clicks`; `100*Clicks/Impressions`, przy tym samym rodzaju kliknięcia |
| CPA | `Spend/AttributedActions` dla wybranego action type, celu i okna; purchase CPA nie jest CAC nowego klienta |
| ROAS | `AttributedPurchaseValue/Spend`; wartość platformowa, nie zysk sklepu |
| Reach / frequency | Reach według platformy dla właściwego scope; częstotliwość `Impressions/Reach`, z uwzględnieniem estymacji źródła |
| Conversion rate | Zdefiniowana konwersja / zdefiniowana baza sesji lub kliknięć; bez danych sesji brak shop CVR |
| Inventory coverage | Sprzedawalny zapas / tempo sprzedaży z wersjonowaną semantyką i oknem; reguły przykładu pozostają w dokumencie 03 |
| Learning / change history | Stan diagnostyczny, czas i zakres zmian, nie KPI wyliczany z samych ROAS/CPA |

Nie uśredniamy średnich ROAS/CPA/CTR bez poprawnych wag; agregat liczymy z właściwych składników. Reach nie sumuje się po dniach lub reklamach jako liczba unikalnych odbiorców. Przy zerowym/niedostępnym mianowniku wynik jest `null` z przyczyną; nie nieskończonością ani zerem. Metryki różnych walut są rozdzielne do jawnej konwersji z wersją kursu.

Źródło techniczne: Meta Insights raportuje na poziomie konta, kampanii, adsetu i reklamy, z parametrami m.in. okresu i atrybucji. Adapter zapisuje użyty zakres i parametry z wynikiem. [Oficjalna dokumentacja Meta Insights](https://developers.facebook.com/documentation/ads-commerce/marketing-api/insights).

### C2. Marża i break-even ROAS [P]

Przyjmujemy spójną bazę bez podatku. W danej kohorcie/okresie `R = NetSales`, `V = COGS + Fulfillment + PaymentFees + OtherVariableCosts`, `A = AdSpend`. Koszty zwrotów uwzględniamy w odpowiednich składnikach; przychodów po zwrotach nie obniżamy drugi raz o tę samą refundację.

```text
ContributionBeforeAds = R - V
PreAdContributionMarginRate = (R - V) / R
ContributionProfit = R - V - A
ContributionMarginAfterAds = ContributionProfit / R
BreakEvenROAS = 1 / PreAdContributionMarginRate
```

Ostatni wzór wynika z warunku `m*R - A = 0`, czyli `R/A = 1/m`. Ma sens dla `R>0` i `m>0`, przy porównywalnym miksie sprzedaży/kosztów i przypisaniu przychodu do tego samego wydatku. Przy `m<=0` nie ma skończonego dodatniego break-even ROAS według tego modelu. Nie jest to próg zysku księgowego firmy: nie obejmuje automatycznie stałych kosztów, kapitału ani przyszłego LTV.

**Przykład [Z]:** R=10 000 PLN, V=6 000 PLN, m=0,40, A=3 000 PLN. ContributionBeforeAds=4 000 PLN, ContributionProfit=1 000 PLN, break-even ROAS=2,5. Iloraz R/A=3,3333 dotyczy tej samej kohorty. Jeśli 10 000 to cała sprzedaż sklepu, iloraz jest blended revenue/ad spend, a nie campaign ROAS Meta. Nie porównujemy go bezpośrednio z atrybuowaną wartością brutto z Ads Managera.

Dla kampanii wieloproduktowej potrzebny jest zgodny miks marż i metoda przypisania przychodu/kosztu. Prosta średnia marż SKU daje błędny próg; stosujemy wagę odpowiedniej sprzedaży lub jawny model estymacji. Niepewne koszty, oczekiwane zwroty lub alokowany ad spend nadają wynikowi ESTIMATED. Przy brakujących krytycznych składnikach nie liczymy pełnego break-even ROAS; można pokazać wynik częściowy z wyraźnym zakresem, ale nie nazwać go potwierdzoną rentownością.

### C3. Jakość, czas i atrybucja [D, P]

`CONFIRMED`, `ESTIMATED`, `UNAVAILABLE` opisują jakość wartości, niezależnie od kompletności, świeżości i dojrzałości. Deterministycznie obliczony wynik może być ESTIMATED, jeśli jego składniki lub alokacja są estymowane. „Pobrane z API” nie znaczy automatycznie CONFIRMED; np. źródłowo modelowane konwersje/reach zachowują ograniczenie źródła. Dla każdego typu metryki adapter dokumentuje klasyfikację i przesłanki.

Engine zapisuje `period`, timezone, cohort/recognition basis, source refs, action type, attribution settings, definicję mianownika, completeness watermark i `maturity: PRELIMINARY|MATURE|UNKNOWN`. MATURE oznacza spełnienie zatwierdzonej reguły stabilizacji danych, nie gwarancję braku przyszłych korekt. Regułę budujemy z obserwowanych opóźnień, zakresu atrybucji i tolerancji sklepu; nie ustawiamy uniwersalnych „24 godzin do pełnej prawdy”.

Osobno: `eventTime`, `sourceUpdatedAt`, `observedAt`, `ingestedAt`, `availableAt`, `computedAt`. Ostatnie pole availableAt wskazuje, kiedy konkretny rekord/rewizja stały się dostępne systemowi. Umożliwia replay bez wiedzy z przyszłości. Korekta historycznych konwersji tworzy rewizję, nie podmienia snapshotu wcześniejszej decyzji.

Porównując ROAS/CPA sprawdzamy: wspólny action type i ustawienia atrybucji, porównywalne okna (w tym dzień tygodnia), opóźnienia konwersji i synchronizacji, liczbę zdarzeń, sezonowość, dostawy/promocje, fazę uczenia oraz historię zmian. Nie sumujemy platformowych konwersji Meta i innych kanałów jako unikalnych zamówień. Atrybucja platformy nie zastępuje eksperymentu inkrementalnego.

Dane o stanie uczenia pobieramy ze źródła, jeśli są dostępne; nie wywnioskujemy ich pewnie z samego wzrostu CPA. Ad Set API opisuje `learning_stage_info` i możliwość niestabilności wyników w trakcie uczenia. Dostępność pól i zachowanie sprawdzamy dla wybranej wersji oraz konta. [Oficjalny Meta Ad Set reference](https://developers.facebook.com/documentation/ads-commerce/marketing-api/reference/ad-campaign). Brak dostępu do informacji o uczeniu daje UNKNOWN i zwiększa ostrożność, zamiast tworzyć fałszywy stan „ustabilizowana”.

### C4. Evidence [P]

Dowód ma ID, tenant/store, zakres zasobu, typ (`OBSERVATION`, `DERIVED_METRIC`, `APPROVED_PROFILE`, `KNOWLEDGE_REFERENCE`), wersję i hash, wartość/jednostkę, czasy, jakość, applicability, definicję obliczenia i zależne evidence IDs. Wiedza ekspercka uzasadnia procedurę, lecz nie zastępuje operacyjnego dowodu spadku sprzedaży. Profil dowodzi celu zadeklarowanego przez sklep, nie faktu o wynikach reklamy.

Przykład metryki i jej identyfikowalności **[Z]**:

```json
{
  "evidenceId": "ev-cpa-202",
  "version": "1",
  "tenantId": "tenant-dtc-01",
  "storeId": "store-01",
  "type": "DERIVED_METRIC",
  "metricKey": "META_PURCHASE_CPA",
  "definitionVersion": "2.0.0",
  "resourceRef": "META_CAMPAIGN:campaign-90",
  "valueDecimal": "48.00",
  "currency": "PLN",
  "unit": "PLN_PER_ATTRIBUTED_PURCHASE",
  "period": {"from": "2026-11-07", "toExclusive": "2026-11-14", "timezone": "Europe/Warsaw"},
  "quality": "ESTIMATED",
  "maturity": "PRELIMINARY",
  "completeness": "COMPLETE_FOR_CURRENT_FEED",
  "attributionSettingsRef": "meta-attribution-01@2",
  "inputEvidenceIds": ["ev-meta-spend-202", "ev-meta-purchases-202"],
  "observedAt": "2026-11-14T07:59:00Z",
  "availableAt": "2026-11-14T08:00:00Z",
  "computedAt": "2026-11-14T08:00:05Z",
  "limitations": ["CONVERSION_REPORTING_NOT_MATURE"],
  "contentHash": "sha256:example-evidence-hash"
}
```

48 PLN nie stanowi samodzielnego progu akcji. Historyczne KPI i ich source refs są przechowywane w scoped magazynie; cache, eksport i endpoint provenance respektują tę samą izolację co tool calls. Hash w przykładzie jest etykietą demonstracyjną; produkcja wylicza rzeczywisty digest kanonicznego rekordu.

## D. Knowledge & Context Building

### D1. Dobór i złożenie kontekstu [P]

1. Z sygnału i typu procesu określ problem, scope, potrzebne metryki oraz dopuszczalne profile/Skills. Nie zaczynaj od załadowania całej wiedzy.
2. Rozwiąż zatwierdzony Store Profile obowiązujący dla decyzji; zaznacz brak celu, konflikt albo nieznane koszty.
3. Pobierz dane i stan jakości z Metrics & Evidence Engine. Minimalny snapshot zawiera również braki; poprawny format nie oznacza gotowości do działania.
4. Wybierz zatwierdzony Playbook i wersję Skill po kategorii problemu i applicability. Dla Inventory Guardian wybór pozostaje deterministyczny; model może prosić o dodatkową zatwierdzoną kompetencję wyłącznie w granicach profilu.
5. Dobierz historię zmian i decyzji tej samej jednostki/zakresu oraz relewantne Outcome. Oddziel obserwację od oceny eksperta i od wyniku samego modelu.
6. Zbuduj manifest i budżet kontekstu. Priorytet mają aktualne ograniczenia, dowody decyzyjne, źródła i niepewności; potem instrukcje i potrzebne przykłady. Usunięcie krytycznego dowodu z powodu limitu wywołuje abstencję, nie udawaną kompletność.
7. Wybierz Skill dla konkretnego uruchomienia i doładuj jego instrukcję oraz niezbędne referencje. Fazy Context Building i Skill Selection mogą mieć wspólny deterministyczny etap wstępny; nie wymagają dwóch wywołań LLM.
8. Przekaż kanoniczny kontekst do istniejącego runtime. Każdy wynik toola musi wejść do rejestru dowodów i jawnej rewizji kontekstu.

Manifest obejmuje `contextManifestId`, cutoff/asOf, snapshot ID, `knowledgeReleaseRef`, Skill/Playbook refs, profile ID/version, metric definition versions, evidence IDs, history cutoff, pominięcia i braki, token budget oraz politykę wyboru modelu. Backend nadaje referencje; model nie może ich podmienić. Historia pobrana przez embeddingi nie jest potrzebna w MVP — scope, ID, kategoria i okres wystarczą dla pierwszych procesów.

**Minimalny kontrakt integracyjny [P]:** `KnowledgeRegistry.resolveRelease`, `StoreProfileService.getApprovedVersion`, `MetricsEvidenceService.query`, `DecisionHistory.findRelevant`, `OutcomeRepository.findObservations`, `KnowledgeContextBuilder.build`. To wewnętrzne porty modułów. Model otrzymuje odpowiednio ograniczone read tools, np. `get_store_business_profile`, `get_attribution_diagnostics`, `get_campaign_learning_state`, `get_recent_campaign_changes`, `get_creative_performance`, `get_decision_history`, `get_outcome_observations`. Istniejące `get_campaign_performance`, `get_product_profitability` i `get_inventory_forecast` korzystają z tego samego Engine; nie powstaje drugi silnik KPI.

Nazwy nowych tools są propozycją kontraktu. Ich parametry, output schemas, wersje i allowlist realizują zasady z [Tool Registry](02-AI-AGENT-ARCHITECTURE.md#5-tool-registry-i-projektowanie-narzędzi). Dla klasyfikacji kontekstu koszty finansowe przekazujemy tylko tym profilom, które ich potrzebują; Support nie dostaje całego profilu kosztowego do pytania o przesyłkę.

### D2. Przenośność i odtwarzalność [D, P]

Kanoniczne wiadomości, dane i kontrakty pozostają własnością aplikacji. ModelGateway dopasowuje je do obsługiwanych ról, tools, schematu i limitu konkretnego modelu. Capability matrix oraz fallback zachowują [projekt gateway](02-AI-AGENT-ARCHITECTURE.md#4-modelgateway-i-spring-ai). Nie zakładamy natywnych Skills, wspólnego limitu kontekstu ani identycznej obsługi JSON Schema u wszystkich dostawców.

Run zapisuje manifest, wersję profilu, wiedzy i schematu, model/provider, ustawienia, wywołania narzędzi, wynik walidacji i koszty. Przechowujemy ustrukturyzowane uzasadnienie, nie prywatny chain-of-thought. Odtworzenie podstaw oznacza dostęp do tych samych danych i wersji; nie obiecuje identycznej odpowiedzi niedeterministycznego modelu.

Do promptu nie wchodzą sekrety i nadmiarowe PII. Tekst produktu, wiadomość klienta i zewnętrzny materiał referencyjny nie zmieniają hierarchii instrukcji. Użytkownik sklepu może zatwierdzić swój profil, ale nie może przez pole strategy podmienić polityki innego tenanta ani nakazać ignorowania walidatora.

## E. Decision-Making Pipeline i kontrakty

### E1. Etapy i alternatywne wyniki [D, P]

`Signal Detection → Context Building → Skill Selection → Analysis → Decision Proposal → Evidence Validation → Policy Evaluation → Execution → Outcome Evaluation`.

| Etap | Właściciel i sprawdzalny wynik |
|---|---|
| Signal Detection | Kod/metryki wykrywają problem; sygnał ma źródło, wersję i scope, nie przesądza reakcji |
| Context Building | Builder tworzy przypięty manifest czterech źródeł i flagi braków |
| Skill Selection | Registry/profile dobiera zatwierdzoną kompetencję i jej wymagania |
| Analysis | Bounded runtime interpretuje dane w kontekście celu sklepu i kryteriów Skill |
| Decision Proposal | Model proponuje wynik i uzasadnienie; backend nadaje kopertę i wersje |
| Evidence Validation | Backend sprawdza ID, zakres, aktualność, zgodność liczb/parametrów i wystarczalność wymaganych przesłanek |
| Policy Evaluation | Istniejący Policy Engine ocenia dopuszczalność, potrzebę zgody, odroczenia lub odmowy |
| Execution | Tylko dozwolona akcja korzysta z AuthorizedAction i Action Executor; pozostałe wyniki nie tworzą mutacji zewnętrznej |
| Outcome Evaluation | Osobno wynik techniczny i obserwacje biznesowe, również dla decyzji o obserwacji lub braku działania |

Przy niewystarczających danych walidator nie „naprawia” uzasadnienia przez wymyślenie faktów. Odrzuca propozycję akcji albo dopuszcza jawny wynik abstencji z identyfikowalnym brakiem. Istnienie evidence ID nie dowodzi sensu całego tekstu: wymagane są zgodność encji/okresów, poprawne wyliczenia i pokrycie przesłanek. Wnioski wymagające oceny eksperta trafiają do eskalacji; same testy struktury nie dowodzą jakości biznesowej.

### E2. Proponowany DecisionProposal 1.2 [P, O-21]

Schematy `1.0` (Inventory Guardian) i `1.1` (Support) z dokumentu 02 pozostają niezmienne. Nowy schemat `1.2` zachowuje kopertę, akcje i ich zabezpieczenia, dodając manifest wiedzy/profilu i jawne gałęzie:

| `recommendation.disposition` w 1.2 | Inwarianty | Skutek procesu |
|---|---|---|
| `RECOMMEND_ACTION` | Jedna typowana akcja, konkretne evidence refs, brak monitorPlan jako zamiennika akcji | Ocena polityki, ewentualna zgoda i wykonanie; wynik nie oznacza APPROVED |
| `NO_ACTION` | `action=null`, wystarczające dane i uzasadnienie braku interwencji | Zakończenie bieżącej oceny; nowe sygnały nadal mogą otworzyć kolejny epizod |
| `MONITOR` | `action=null`, dowody, warunki ponownej oceny i ograniczony plan obserwacji | `MONITORING`, trwały timer/sygnał i kolejna analiza; bez mutacji reklamy |
| `INSUFFICIENT_DATA` | `action=null`, jawne required/missing evidence i wpływ braku | `WAITING_DATA`, dozwolone odświeżenie źródła lub interwencja, termin eskalacji |
| `ESCALATE` | `action=null`, przyczyna wymagająca człowieka i dostępne dowody | Eskalacja; nie omija polityk dla późniejszej akcji |

Przy MONITOR backend waliduje zakres odczytów, terminy, allowed metrics, koszt, maksymalną liczbę reanaliz i deadline. Nie daje modelowi dowolnego harmonogramu ani bezterminowej pętli. Warunek może być typowany: osiągnięcie dojrzałości danych, określona obserwacja lub zatwierdzony próg z profilu/polityki. Jeśli z góry nie wiadomo, czy prognoza/metryka jest wiarygodna, używamy INSUFFICIENT_DATA, a nie kosmetycznego MONITOR. Na deadline bez rozstrzygnięcia zapisujemy stan i eskalację według polityki.

Mapowanie legacy: `ACT → RECOMMEND_ACTION`, `WAIT_FOR_DATA → INSUFFICIENT_DATA`, `NO_ACTION → NO_ACTION`, `ESCALATE → ESCALATE`. Nie zakładamy MONITOR na podstawie tekstu starej odpowiedzi. Starego NO_ACTION nie zmieniamy retroaktywnie w plan obserwacji. Adapter read model może wyświetlać zunifikowany `decisionKind`, lecz zapis źródłowy, schemaVersion i rewizja pozostają zachowane. Konsument nieobsługujący `1.2` odrzuca go jawnie; nie mapuje MONITOR na zamknięty NO_ACTION.

**Migracja [P]:** rejestracja nowego schematu → walidatory/allowlist profili → projekcje UI i Decision History → wersjonowany workflow z MONITORING i terminami → ewaluacja/fault tests → opt-in profilu. Nowa wersja nie zamienia automatycznie aktywnych workflow ani istniejących approvals. Obsługa pól Support z 1.1 zachowuje trigger, ownership i kontrolę draftów; samo rozszerzenie rodzajów decyzji nie znosi blokady wysyłki.

Kopertę nadal tworzy backend: `schemaVersion`, IDs/revision, subject (`opportunityId` lub Support refs), `createdAt/expiresAt`, `contextSnapshotId`, `contextManifestId`, `knowledgeReleaseRef`, `storeProfileRef`. Model zwraca recommendation i findings; backend rozwiązuje referencje i utrwala ich zatwierdzoną postać. `monitorPlan` i `observationPlan` są walidowane, a potem planowane przez aplikację. Pierwszy dotyczy decyzji „poczekaj i sprawdź”, drugi pomiaru po decyzji/akcji.

Szkice kontraktów Java **[P]**, bez typów Spring AI i SDK dostawcy:

```java
public enum DecisionDisposition {
    RECOMMEND_ACTION,
    NO_ACTION,
    MONITOR,
    INSUFFICIENT_DATA,
    ESCALATE
}

public record KnowledgeRef(String knowledgeId, String version, String contentHash) {}

public record StoreProfileRef(String profileId, long version) {}

public record EvidenceRef(String evidenceId, String version) {}

public record ContextManifest(
    String manifestId,
    Instant cutoff,
    String snapshotId,
    StoreProfileRef storeProfile,
    List<KnowledgeRef> knowledge,
    List<EvidenceRef> evidence,
    List<String> missingRequirements
) {}

public record MonitorPlan(
    Instant nextReviewAt,
    Instant deadline,
    int maxReanalyses,
    List<String> metricKeys,
    List<String> conditionRefs
) {}

public record DecisionRecommendation(
    DecisionDisposition disposition,
    ActionSpec action,
    MonitorPlan monitorPlan,
    List<EvidenceRef> evidence,
    List<String> missingRequirements,
    List<String> reasonCodes,
    String summary,
    List<String> uncertainties
) {}

public interface MetricsEvidenceService {
    MetricSnapshot query(MetricQuery query, TrustedStoreScope scope);
}

public interface KnowledgeContextBuilder {
    BuiltContext build(DecisionTask task, TrustedStoreScope scope);
}

public interface OutcomeEvaluator {
    OutcomeObservation evaluate(ObservationPlan plan, TrustedStoreScope scope);
}
```

To projekcje kontraktów, nie kompletna definicja wszystkich DTO: `ActionSpec`, `MetricQuery` i pozostałe typy odnoszą się do typowanych portów aplikacji. `action`/`monitorPlan` są nullable wyłącznie według tabeli inwariantów; walidujemy to przez warianty JSON Schema i walidator domenowy. Java `TrustedStoreScope` pochodzi z backendu, nie z argumentów LLM.

Przykładowy pełny MONITOR **[Z]**:

```json
{
  "schemaVersion": "1.2",
  "proposalId": "prop-marketing-202",
  "revision": 1,
  "opportunityId": "opp-marketing-202",
  "agentRunId": "run-marketing-202",
  "contextSnapshotId": "ctx-marketing-202-v1",
  "contextManifestId": "manifest-marketing-202-v1",
  "knowledgeReleaseRef": "marketing-pack@1.0.0",
  "storeProfileRef": {"profileId": "store-profile-01", "version": 3},
  "createdAt": "2026-11-14T08:01:00Z",
  "expiresAt": "2026-11-14T08:06:00Z",
  "recommendation": {
    "disposition": "MONITOR",
    "action": null,
    "monitorPlan": {
      "nextReviewAt": "2026-11-15T08:00:00Z",
      "deadline": "2026-11-17T08:00:00Z",
      "maxReanalyses": 2,
      "metricKeys": ["META_PURCHASE_CPA", "META_PURCHASE_ROAS"],
      "conditionRefs": ["maturity-rule-meta-01@2"]
    },
    "evidence": [
      {"evidenceId": "ev-cpa-202", "version": "1"},
      {"evidenceId": "ev-roas-202", "version": "1"},
      {"evidenceId": "ev-budget-guard-202", "version": "1"}
    ],
    "missingRequirements": [],
    "reasonCodes": ["CONVERSION_REPORTING_NOT_MATURE", "NO_CURRENT_HARD_LIMIT_BREACH"],
    "summary": "Wyniki wymagają obserwacji do czasu dojrzewania konwersji; nie proponuję teraz zmiany budżetu.",
    "uncertainties": ["Wartość CPA może zmienić się po aktualizacji raportowania."]
  }
}
```

Przykładowy manifest rejestruje również `ev-roas-202` dla obserwowanego ROAS i `ev-budget-guard-202` dla deterministycznego sprawdzenia aktualnych twardych limitów; walidacja wymaga ich istnienia i zgodności scope. Terminy i limit reanaliz są parametrami przykładu, nie uniwersalną regułą. `expiresAt` dotyczy dopuszczenia propozycji do zaplanowania; przyjęty przed terminem MONITOR działa do zatwierdzonego deadline, a każda reanaliza ma nowy run, snapshot i ważność.

W 1.2 pole `evidence` z wersjami zastępuje listę `evidenceIds` wewnątrz recommendation; adapter mapuje ID legacy na wersje utrwalone w historycznym snapshotcie, nigdy na „najnowsze”. Zmiana jest jawnie wersjonowana. Test kontraktowy musi objąć tę różnicę; UI używa read modelu `evidenceRefs` niezależnego od transportu propozycji modelu.

## F. Decision Experience i Evaluation

### F1. Historia i model obserwacji [D, P]

Decision History jest projekcją istniejących decyzji/audytu, nie drugim źródłem prawdy o wykonaniu. Łączy signal/opportunity/support case, snapshot/manifest, wersje wiedzy i profilu, proposal/revision, ocenę polityki, approval lub odmowę, action ledger, wynik techniczny i Outcome. Zachowuje również NO_ACTION, MONITOR, brak danych i odrzucenia; historia samych „sukcesów” zniekształca ocenę.

`ObservationPlan` określa subject, baseline window, follow-up windows, metryki i ich definicje, okres dojrzewania, minimalną jakość/kompletność, znane confounders, sposób porównania, limit prób i właściciela eskalacji. Plan tworzymy przed oceną wyniku, aby nie dobierać okna dopiero do korzystnego wykresu. Techniczna weryfikacja nadal zachowuje swój krótki deadline i nie czeka na biznesowy follow-up.

`OutcomeObservation` ma `observationId/revision`, decision/action ref, plan ref, baseline/follow-up evidence, daty pomiaru i availableAt, brakujące dane, maturity, confounders, evaluation method i `attributionStrength`. Statusy jakości procesu: `PENDING`, `OBSERVED`, `INCONCLUSIVE`, `CENSORED`, `SUPERSEDED`. Ocena biznesowa może być `FAVORABLE`, `UNFAVORABLE`, `MIXED`, `UNDETERMINED` względem zadeklarowanego celu, niezależnie od statusu technicznego. Favorable nie znaczy caused by agent.

Proponowane okna **[Z]**:

| Proces | Pomiar | Ograniczenie |
|---|---|---|
| Pauza reklamy | Krótka weryfikacja stanu według dokumentu 03; obserwacja zapasu/sprzedaży/wydatku po 24 h i w dojrzałym oknie np. 7 dni | Dostawa, inna reklama i przesunięcie budżetu mogą zakłócać porównanie |
| Diagnoza/budżet marketingowy | Baseline i follow-up o porównywalnych dniach oraz odpowiedniej dojrzałości konwersji | Każda kolejna zmiana adsetu może rozpocząć nowe okno lub ocenzurować stare |
| MONITOR | Odczyt przy nextReviewAt lub warunku, koniec najpóźniej deadline | Mierzymy czy dane dojrzały i czy rekomendacja wymaga zmiany |
| NO_ACTION | Ocena ekspercka/historyczny follow-up w wybranych przypadkach, bez obowiązkowej stałej pętli | Nie znamy automatycznie kontrfaktycznego wyniku wykonania akcji |
| Support | Przyjęcie/dostarczenie według dostępnych dowodów oraz czas reakcji, ponowne otwarcie/eskalacje | Sama liczba automatycznych odpowiedzi nie dowodzi rozwiązania problemu |

Przy późnej korekcie danych nowa rewizja obserwacji wskazuje poprzednią; oryginalny kontekst decyzji pozostaje niezmienny. Brak danych w deadline daje INCONCLUSIVE, nie sztuczne zero efektu. Zmiana strategii sklepu w oknie jest confounderem i może wymagać rozdzielenia pomiarów.

### F2. Korelacja i przyczynowość [D, P]

Domyślnie używamy `attributionStrength=OBSERVED_ASSOCIATION` albo `UNKNOWN`. Nie wyliczamy „oszczędności spowodowanej AI” przez samo odjęcie wydatku po pauzie. Sezonowość, promocja, cena, dostępność, miks produktów, zmiany innych kanałów i regresja do średniej mogą zmienić wynik.

Wniosek przyczynowy wymaga osobnego projektu eksperymentu lub uzasadnionej metody z opisanymi założeniami, np. grupy kontrolnej i dopuszczonego porównania. W MVP nie wdrażamy automatycznie silnika eksperymentów; przechowujemy pole metody i ograniczenia, aby później móc go dodać. Retrospektywna poprawa CPA bez takiego projektu pozostaje obserwacją, niezależnie od confidence modelu.

### F3. Offline Evaluation Framework [D, P]

Każdy przypadek obejmuje snapshot danych dostępnych w chwili decyzji, cutoff, manifest wiedzy, profil, capabilities, dopuszczone akcje, oczekiwany zbiór poprawnych wyników i rubric eksperta. Historyczne źródła są dwuczasowe: filtrujemy nie tylko eventTime, lecz również availableAt. Później skorygowana konwersja/dostawa nie może zasilić analizy „jak gdyby była znana wcześniej”. Oddzielamy ocenę jakości decyzji ex ante od oceny obserwowanego wyniku ex post.

Jeśli starsze dane nie mają zachowanego as-of/availableAt, oznaczamy przypadek jako rekonstruowany i ujawniamy ograniczenie. Nie wymyślamy historycznych czasów dostępności ani nie włączamy takiego przypadku bez oznaczenia do ścisłego benchmarku replay. Od startu pilota archiwizujemy kolejne rewizje potrzebne do wiarygodnego odtwarzania.

Zestawy dzielimy według czasu, sklepów i typu problemu na development, validation i zamrożony holdout. Scenariusze różnych okresów tej samej kampanii nie mogą przeciekać między zbiorami przez kopiowany kontekst. Ekspert opisuje uzasadnione alternatywy; poprawnym wynikiem może być MONITOR zamiast jednej arbitralnej akcji. Sporne etykiety przechodzą adjudykację, a nie automatyczne uznanie jednego sędziego LLM.

| Wymiar | Pomiar i bramka |
|---|---|
| Bezpieczeństwo wykonania | Nieautoryzowane cele, nieznane tools, naruszenie tenant/scope/policy; każda taka mutacja blokuje release |
| Uzasadnienie | Pokrycie przesłanek dowodami, zgodność liczb, brak fikcyjnego źródła i przyczynowego overclaim |
| Jakość decyzji | Zgodność z zakresem odpowiedzi eksperta, uwzględnienie celu i danych, koszt zbędnej interwencji |
| Abstencja | Trafne odróżnienie MONITOR, INSUFFICIENT_DATA, NO_ACTION i ESCALATE; brak premiowania „zawsze nic nie rób” |
| Operacyjność | Poprawność schematu, wymagane capabilities, liczba narzędzi, latency i koszt całego run |
| Odporność | Braki, konflikty, prompt injection, wersje API, zmiana profilu i granice czasu |

Porównujemy pary skill release/model przy tym samym profilu, danych i dostępnym budżecie. Wykonujemy kilka prób dla niedeterministycznych modeli i raportujemy rozrzut, wielkość próby oraz rozkład po klasach/sklepach; jedna średnia „accuracy” nie wystarczy. Ważne błędy opisujemy oddzielnie, np. false intervention na rentownej kampanii. **O-23:** konkretne progi jakości i minimalny zestaw wymagają danych eksperckich i uzgodnienia pilota.

Golden tests obejmują wzory i lineage bez LLM; testy kontraktowe obejmują wszystkie wersje propozycji i provider adapters; testy workflow obejmują MONITOR deadline, brak danych, korekty i idempotencję planowania. Shadow ocenia decyzje bez skutku zapisu; nie zastępuje testów executor/API z dokumentu 02.

### F4. Bezpieczna aktualizacja wiedzy [D, P]

Outcome może utworzyć kandydaturę przypadku do review albo zgłoszenie problemu w Skill. Uprawniony ekspert ocenia ją, uzupełnia testy i przygotowuje release. Agent nie edytuje sam procedur, reguł, profilu strategii ani wag preferencji. Nawet seria dodatnich obserwacji bez wiarygodnej kontroli nie nadaje prawa do samodzielnego zwiększania budżetów.

Aktywacja wersji wymaga przejścia regresji, oceny błędów krytycznych, jawnego zatwierdzenia i możliwości rollbacku do release. Historyczne decyzje zachowują oryginalną wersję; nowe wykonanie nadal podlega bieżącym politykom. Dokument 02 definiuje mechanikę wersjonowania runtime, a tutaj wersjonujemy wiedzę i sposób jej ewaluacji.

## G. Marketing Knowledge Pack — pierwsza wersja

### G1. Wspólny kontrakt i zakres [P]

Release `marketing-pack@1.0.0` obejmuje sześć Skills wymienionych poniżej, trzy Playbooks z A2, rejestr źródeł i przypadki ewaluacyjne. Każdy Skill ma metadata zgodne z formatem z dokumentu 02, required evidence, limitations, output schema, owners i eval refs. Domyślnie nie ładuje wszystkich pozostałych Skills; Playbook wybiera tylko potrzebne kompetencje. Nie wymaga osobnego agenta dla każdej kompetencji.

Na granicy modelu proponujemy `SkillResult<Findings> = {recommendation, findings}`: recommendation ma inwarianty z E2, a findings jest typowanym artefaktem diagnostycznym zapisywanym w AgentRun i wiązanym z propozycją. Kopertę DecisionProposal składa backend. Findings nie jest ActionSpec ani upoważnieniem; np. szkic wariantów budżetu nie trafia do executor jako niezweryfikowany kod. JSON Schema dla wybranego Skill zamyka zarówno recommendation, jak i jego findings; niezdefiniowane pola nie przechodzą walidacji.

W każdym findings liczby pochodzą z `metricRefs`, a wiedza proceduralna z `knowledgeRefs`. Hipotezy mają `supportingEvidence`, `contradictingEvidence`, `uncertainties` i kolejny dozwolony krok diagnostyczny. Nie zapisujemy nieudokumentowanych liczb w tekście uzasadnienia jako faktów.

**Zakres wykonania MVP:** Marketing Pack dostarcza diagnostykę, obserwację i propozycje. Nie dodaje uprawnień do zwiększania budżetów, tworzenia kreacji ani kampanii. RECOMMEND_ACTION może wskazać tylko typ/zakres dostępny w danym profilu i Action Registry. Gdy pożądana operacja nie jest dopuszczona, Skill zwraca ESCALATE i opisuje wariant w findings jako materiał doradczy, bez wykonalnej akcji. Budżetowe kontrakty rozszerzeń z dokumentu 02 wymagają oddzielnego wdrożenia, polityki i testów.

### G2. `campaign-diagnosis`

| Element | Specyfikacja |
|---|---|
| Cel | Rozróżnić wiarygodne pogorszenie wyników, problem danych i zmienność; wskazać prawdopodobne przyczyny bez automatycznej ingerencji. |
| Dane i tools | Campaign performance, CPA/ROAS i składniki, okres porównawczy, attribution/maturity, marża, zapasy, historia zmian, cel sklepu. Tools: `get_campaign_performance`, `get_attribution_diagnostics`, `get_campaign_learning_state`, `get_recent_campaign_changes`, `get_product_profitability`, istniejący forecast. Brak mapowania SKU jest jawny. |
| Procedura | 1. Zweryfikuj jakość i porównywalność okresów. 2. Sprawdź próbę, opóźnienia i learning. 3. Rozdziel zmianę CPM, kliknięć i konwersji, zachowując wspólną bazę. 4. Sprawdź marżę, zapas, promocje i zmiany. 5. Zbuduj hipotezy z dowodami za/przeciw. 6. Oceń, czy interwencja ma uzasadnienie względem celu sklepu. |
| Kryteria rekomendacji | Dopuszczalna interwencja wymaga materialnego problemu na porównywalnych danych, zidentyfikowanego zakresu i proporcjonalnej dostępnej akcji. Stabilne wyniki zgodne z celem dają NO_ACTION; niedojrzałe, poprawnie zidentyfikowane wyniki mogą dać MONITOR. |
| Warunki wstrzymania | Nieporównywalna atrybucja, brak krytycznych danych lub niespójny spend → INSUFFICIENT_DATA. Niejednoznaczna przyczyna wymagająca oceny człowieka → ESCALATE. Sam spadek ROAS nie wystarcza. |
| Structured output | DecisionRecommendation + `CampaignDiagnosisFindings`: `hypotheses[]`, metricRefs, maturity assessment, learningRef, changeRefs, goalAlignment, requiredChecks. Hipoteza nie ma pola `provenCause=true` bez podstaw. |
| Scenariusze testowe | ROAS 3,6→2,4 przy niedojrzałych konwersjach → MONITOR, jeśli nie naruszono twardych granic; taki sam spadek przy niezgodnych oknach → INSUFFICIENT_DATA; potwierdzony stabilny wynik zgodny z marżą → NO_ACTION; niska marża mimo wysokiego ROAS → diagnoza/eskalacja zamiast „skaluj”. |

### G3. `budget-optimization`

| Element | Specyfikacja |
|---|---|
| Cel | Ocenić zasadność i zakres propozycji budżetowej przy uwzględnieniu ekonomii, planu wydatków, ryzyka i możliwości realizacji. |
| Dane i tools | Budżet na właściwym poziomie campaign/adset, strategia stawek/cel, pacing, spend, marża/CPA, maturity, learning, ograniczenia zapasu i ostatnie edycje. Tools: performance, store profile, profitability, learning, change history i scoped `get_budget_pacing`. |
| Procedura | 1. Ustal właściciela budżetu i zakres współdzielenia. 2. Sprawdź plan vs wykonanie i kumulatywne zmiany. 3. Zbadaj ekonomikę oraz dojrzałość próby. 4. Oszacuj warianty tylko deterministycznymi metodami z jawnymi założeniami. 5. Oceń koszt ingerencji i ograniczenia. 6. Wybierz dozwoloną rekomendację, obserwację albo materiał do człowieka. |
| Kryteria rekomendacji | Zmiana musi mieć potwierdzony scope, uzasadnienie względem celu i planu, dopuszczoną akcję oraz aktualny stan bazowy. Procent zmiany wynika z zatwierdzonego zakresu/polityki, nie „reguły branżowej”. W MVP niedopuszczona zmiana daje ESCALATE z analizą wariantów. |
| Warunki wstrzymania | Nieznany poziom budżetu, konflikt profilu, niedojrzałe konwersje, kolejna ingerencja w trwającym oknie, brak kosztów wymaganych do oceny albo brak dostępnej akcji. Nie utożsamiaj daily budget z gwarantowanym sztywnym limitem dziennego spend. |
| Structured output | DecisionRecommendation + `BudgetAssessmentFindings`: budgetScope, currentBudgetRef, pacingRefs, candidateScenarios, cumulativeChangeRefs, expectedTradeoffs i limitations. Wykonalny ActionSpec — tylko jeśli dostępny — zawiera bezwzględny desired amount/walutę i precondition, nie ponawialne „minus 10%”. |
| Scenariusze testowe | Wiele małych zmian próbujących obejść limit → odmowa wykonania; próba zmiany adsetu z budżetem na campaign → brak niewłaściwej akcji; niedojrzałe konwersje → MONITOR; wzrost budżetu przy polityce MVP zabraniającej wzrostu → ESCALATE, brak mutacji. |

### G4. `creative-fatigue-analysis`

| Element | Specyfikacja |
|---|---|
| Cel | Wykryć przesłanki możliwego zmęczenia kreacji i odróżnić je od zmian aukcji, odbiorców, oferty i danych. |
| Dane i tools | Ad/creative mapping, daty i warianty kreacji, impressions/reach/frequency, CTR/CPC/CPM/CPA, grupy odbiorców, placement mix i change history. Tools: `get_creative_performance`, performance, attribution diagnostics, recent campaign changes. Dostępne breakdowns są capability connectora. |
| Procedura | 1. Potwierdź tę samą kreację i porównywalny miks. 2. Zbadaj trendy częstotliwości i kliknięć wraz z konwersją. 3. Sprawdź zmianę CPM, audience i placementów. 4. Oceń próbę i maturity. 5. Sformułuj hipotezę oraz potrzebę testu/nowego materiału dla człowieka. |
| Kryteria rekomendacji | Zbieżne, utrzymujące się przesłanki na porównywalnej bazie mogą uzasadniać ESCALATE z propozycją testu. Brak przesłanek daje NO_ACTION. Nie ma uniwersalnego frequency >X oznaczającego automatyczne zatrzymanie. |
| Warunki wstrzymania | Brak reach lub zmiana zakresu estymacji, nowa kreacja pod starym ID, mała próba, istotna zmiana audience/placementów, brak możliwości rozdzielenia miksu. Przy przydatnych, lecz niedojrzałych danych — MONITOR; przy wymaganym braku — INSUFFICIENT_DATA. |
| Structured output | DecisionRecommendation + `CreativeFatigueFindings`: creativeRef/fingerprint, fatigueHypothesis, supporting/contradicting evidence, mixChanges, suggestedTestBrief i uncertainty. Test brief nie publikuje reklamy. |
| Scenariusze testowe | Rosnąca frequency bez pogorszenia metryk → brak automatycznej pauzy; CTR spada wskutek zmiany placement mix → nie ogłaszaj zmęczenia jako faktu; brak reach → brak zmyślonej częstotliwości; porównywalne pogorszenie zbieżnych wskaźników → uzasadniona propozycja testu dla człowieka. |

### G5. `profitability-analysis`

| Element | Specyfikacja |
|---|---|
| Cel | Ocenić wkład sprzedaży/kampanii/SKU do marży po znanych kosztach i określić granice tej oceny. |
| Dane i tools | NetSales, COGS, fulfillment, fees, returns, ad spend, podatkowa/walutowa baza i alokacje. Tools: `get_product_profitability`, `get_campaign_performance`, scoped `get_store_profitability`, store profile i evidence/provenance. |
| Procedura | 1. Sprawdź bazę i kompletność kosztów. 2. Pobierz obliczenia z Engine. 3. Oddziel marżę przed reklamą od po reklamie. 4. Oceń zasadność porównania z break-even ROAS. 5. Sprawdź miks/zwroty/atrybucję. 6. Wyjaśnij wynik i niepewności w kontekście celu. |
| Kryteria rekomendacji | Spójna ekonomia może uzasadnić brak działania, potrzebę diagnozy lub dopuszczoną reakcję. Margines blisko progu przy dużej niepewności nie daje fałszywej pewności. Niepotwierdzony campaign profit pozostaje estymacją. |
| Warunki wstrzymania | Nieznany COGS krytycznej części sprzedaży, niespójny VAT/waluta, nieznana metoda alokacji, podwójne liczenie refundacji albo niezdefiniowany mianownik. Wtedy INSUFFICIENT_DATA lub ESCALATE zamiast dopisania typowej marży. |
| Structured output | DecisionRecommendation + `ProfitabilityFindings`: scope, contributionBeforeAdsRef, contributionProfitRef, marginRateRef, breakEvenRoasRef lub null, costCoverage, allocationRefs, limitations i goalAlignment. |
| Scenariusze testowe | Ten sam ROAS=3 przy m=0,50 i m=0,25 → różne wnioski, uproszczone progi 2 i 4 tylko przy zgodnej bazie; m<=0 → brak skończonego BE; brak COGS → Unavailable; refundacja już w NetSales → bez ponownego odjęcia; suma Meta+Google nie tworzy unikalnej sprzedaży. |

### G6. `campaign-learning-analysis`

| Element | Specyfikacja |
|---|---|
| Cel | Określić, czy stan uczenia i historia zmian ograniczają wiarygodność oceny lub zasadność ingerencji. |
| Dane i tools | Stan learning właściwego adsetu, cel optymalizacji, zdarzenia i ich dojrzałość, czas/typ edycji, stan dostarczania i problemy. Tools: `get_campaign_learning_state`, `get_recent_campaign_changes`, performance i attribution diagnostics. |
| Procedura | 1. Ustal poziom hierarchii. 2. Odczytaj dostępny stan i czas. 3. Powiąż go ze zmianami i celem optymalizacji. 4. Oddziel niestabilność od naruszeń twardych limitów. 5. Określ dalszą obserwację lub potrzebę eksperckiej interwencji. |
| Kryteria rekomendacji | MONITOR jest właściwy, gdy stan i jakość danych uzasadniają czekanie, a twarde limity nie są naruszone. NO_ACTION może oznaczać brak problemu uczenia. Learning nie blokuje ochrony przed potwierdzonym naruszeniem polityki lub brakiem zapasu. |
| Warunki wstrzymania | Brak/nieaktualny learning state, brak źródłowej historii zmian, nieznany optimization event albo nieobsługiwana wersja pola. Nie zakładamy, że każda zmiana 20% lub dokładnie N konwersji stanowi uniwersalny próg. |
| Structured output | DecisionRecommendation + `LearningAssessmentFindings`: learningStateRef, optimizationGoalRef, significantChangeRefs, assessmentLimitations, allowedObservationConditions i hardConstraintCheckRefs. |
| Scenariusze testowe | Learning + zmienne CPA bez naruszeń → MONITOR; learning + potwierdzony niebezpieczny stan zapasu → nie blokuj osobnego Inventory Guardian; brak pola → UNKNOWN i abstencja zależna od wymagań; stara edycja nie może udawać właśnie rozpoczętej fazy. |

### G7. `scaling-readiness`

| Element | Specyfikacja |
|---|---|
| Cel | Ocenić gotowość do wzrostu przy ekonomii, dojrzałych danych, zapasie i możliwościach operacyjnych, bez utożsamiania dobrego historycznego ROAS z gwarancją skalowania. |
| Dane i tools | Marża, CPA/ROAS i próba, learning, ostatnie zmiany, cash/budget plan, zapasy/dostawy i zdolność realizacji zamówień. Tools: profitability, performance, store profile, inventory forecast, learning i scoped `get_operational_capacity`. Brak capacity/cash jest jawny. |
| Procedura | 1. Sprawdź cel wzrostu i limity. 2. Oceń dojrzałość rentowności oraz ryzyko miksu. 3. Sprawdź zapas i fulfillment. 4. Sprawdź ostatnie skalowanie i współdzielenie budżetu. 5. Opisz readiness i ograniczenia zamiast mechanicznego mnożenia wyników. |
| Kryteria rekomendacji | Zgodna ekonomia, stabilne dane i możliwości operacyjne uzasadniają materiał do decyzji człowieka. W MVP wzrost budżetu pozostaje ESCALATE z planem oceny. NO_ACTION, gdy wzrost nie jest celem lub nie ma uzasadnienia. MONITOR, gdy trzeba poczekać na dojrzałość. |
| Warunki wstrzymania | Nieznana marża, cash constraint, niepotwierdzona dostawa, niski zapas, konflikt strategii, brak danych o capacity, niedojrzałe konwersje lub zbyt świeża ingerencja. Wysoki ROAS nie znosi tych warunków. |
| Structured output | DecisionRecommendation + `ScalingReadinessFindings`: `READY_FOR_HUMAN_REVIEW/NOT_READY/UNKNOWN`, economicEvidence, inventory/capacityRefs, riskFactors, proposedReviewPlan i unknowns. READY nie znaczy `AuthorizedAction`. |
| Scenariusze testowe | Rentowna kampania, zapas na 2,6 dnia → brak rekomendacji automatycznego wzrostu; wysoki ROAS przy brakującym COGS → UNKNOWN; dojrzała rentowność i capacity, lecz zakaz wzrostu w MVP → ESCALATE bez mutacji; zmiana celu na PROFITABILITY nie uchyla twardych limitów ani nie nakazuje wzrostu. |

### G8. Dane testowe i progi [P]

Każda procedura wiąże progi z konkretnym celem, marginesem błędu, wielkością próby, horyzontem i zatwierdzoną konfiguracją. Progi są wersjonowanymi inputami, z jednostką i zakresem, a nie ukrytymi liczbami w promptach. W każdym Skill testujemy także profil z brakującym progiem: model nie może wymyślić go w trakcie analizy i nadać mu statusu zatwierdzonego.

Ekspert przygotowuje przypadki z odpowiedziami dopuszczalnymi, w tym kontrprzykłady do popularnych heurystyk. Wynik porównujemy na poziomie dyspozycji, zakresu, dowodów i niepewności. Kolejne Skills mogą czytać artefakt poprzedniego jako hipotezę, ale nadal odwołują się do pierwotnych dowodów; powtarzanie wniosku przez kilka modeli nie zwiększa automatycznie jego wiarygodności.

## H. Zakres MVP, wdrożenie i kwestie otwarte

### H1. Minimalny zakres [D, P]

| Obszar | MVP | Dalszy rozwój po wykazaniu potrzeby |
|---|---|---|
| Domain Knowledge | Pliki Skills/Playbooks, metadata/provenance, przypięty release i review; Marketing Pack v1 | Większa biblioteka, retrieval przy skali |
| Store Knowledge | Zatwierdzany profil, referencje kosztów/polityk, automatyczne kandydatury i konflikty | Rozbudowane scenariusze i personalizacja |
| Operational Data | Deterministyczny Engine, Evidence, lineage, jakość i podstawowe maturity | Bogatsze modele opóźnień/niepewności i źródła |
| Experience | Decision History, podstawowe okna Outcome i ocena ograniczeń | Kontrolowane eksperymenty i formalne metody przyczynowe |
| Evaluation | Historyczne scenariusze, eksperci, regresja i benchmark skill/model | Większa automatyzacja benchmarków z zachowaniem nadzoru |
| Runtime | Dotychczasowy gateway i bounded loop, rozszerzony kontekst i kontrakt | Wyłącznie rozszerzenia poparte ewaluacją |
| RAG / Vector DB | Opcjonalne, bez wymogu infrastruktury w pierwszym wydaniu | Gdy katalog nieustrukturyzowanej wiedzy przekroczy praktyczne możliwości filtrowania/ładowania |
| Fine-tuning / samodzielna strategia | Poza MVP | Odrębna decyzja po ocenie danych, jakości i bezpieczeństwa |

Pakiet marketingowy może wystartować jako analiza L0/L1 w Profit Monitor/Ad Spend Guardian. Nie dokładamy sześciu nowych automatyzacji ani usług. Inventory Guardian i Support konsumują te same komponenty w odpowiednio ograniczonym zakresie. RAG nie jest synonimem czterech źródeł; profil, KPI i historia są głównie ustrukturyzowanymi danymi.

**[P] Kolejność implementacji:** rejestr źródeł i definicje metryk → wersjonowany Store Profile → manifest/snapshot i referencje → Registry/Marketing Pack z fixtures → ewaluacja offline → MONITOR/kontrakt 1.2 z read models → shadow/L1 → Outcome od pierwszych akcji → ograniczone L2 tylko w istniejącym dopuszczonym zakresie. Obserwacje nie muszą czekać na zaawansowane eksperymenty.

### H2. Minimalne kryteria odbioru [P]

1. Dla każdej propozycji można wskazać profil, release wiedzy, definicje, źródła i cutoff; obca referencja tenanta jest odrzucona.
2. Engine przechodzi testy wzorów, podatkowej/walutowej bazy, zerowych mianowników, zwrotów, alokacji i maturity. LLM nie staje się obejściem brakującej metryki.
3. NO_ACTION, MONITOR, INSUFFICIENT_DATA i RECOMMEND_ACTION są rozróżnione w schemacie, workflow, historii i UI; legacy `1.0/1.1` przechodzą regresję bez niejawnej zmiany semantyki.
4. Aktualizacja profilu/wiedzy jest wersjonowana i audytowana; model nie ma ścieżki samodzielnej aktywacji zmiany.
5. Outcome rozdziela potwierdzenie techniczne od efektu biznesowego, ujawnia confounders i nie deklaruje przyczynowości bez odpowiedniego pomiaru.
6. Każdy z sześciu Skills ma testy ze scenariuszami abstencji i naruszeń; release przechodzi uzgodnione bramki eksperckie oraz bezpieczeństwa.

### H3. Nowe kwestie wymagające decyzji [O]

| ID | Decyzja potrzebna przed wdrożeniem | Proponowany właściciel / wpływ |
|---|---|---|
| O-18 | Kto odpowiada za wiedzę, reviewDueAt, źródła dopuszczone i aktywację tenant override/release? | Product + ekspert marketingu + tech lead; blokuje produkcyjny Marketing Pack bez właściciela |
| O-19 | Jak zatwierdzamy cel, ryzyko i horyzont; jakie precedence obowiązuje przy konflikcie COGS/budżetu/profilu? | Właściciel sklepu + domena Commerce; blokuje decyzje wymagające brakującego trade-off |
| O-20 | Jakie recognition/tax/cost/allocation definitions, sample criteria i maturity rules obowiązują w pilocie? | Finance/data owner + marketing expert; dotyczy porównywalności KPI i progów |
| O-21 | Czy przyjmujemy kontrakt 1.2, jego migrację, nowy MONITORING oraz limity reanalizy? | Backend/AI/UI leads; wymaga wspólnego testu konsumentów i aktywnych workflow |
| O-22 | Jakie baseline/follow-up windows, warunki CENSORED i dopuszczalne twierdzenia o efekcie stosujemy? | Product + analityk/ekspert; wpływa na Outcome i prezentację wyników |
| O-23 | Kto etykietuje przypadki, jaki jest holdout i progi release dla każdego skill/model oraz kosztu? | Ekspert domenowy + QA/AI; blokuje automatyczną promocję wersji bez ewaluacji |

Te pytania dotyczą szczegółów realizacji D-08/D-09, nie ponownego rozstrzygnięcia, czy wiedza i ewaluacja są w MVP. Pozostałe pytania produktu i UI zachowują numery z dokumentów 01 i 04.

### H4. Źródła zewnętrzne i ograniczenia weryfikacji

Podczas przygotowania sprawdzono oficjalne strony [Meta Insights](https://developers.facebook.com/documentation/ads-commerce/marketing-api/insights) oraz [Ad Set reference](https://developers.facebook.com/documentation/ads-commerce/marketing-api/reference/ad-campaign). Wspierają kontrakt raportowania i pozyskiwanie informacji o uczeniu; nie ustanawiają strategii rentowności konkretnego sklepu. Definicje finansowe C2 wynikają z jawnie podanego modelu algebraicznego i wymagają uzgodnienia bazy danych sklepu.

Strona Meta Business Help o istotnych edycjach/learning wymagała logowania lub była niedostępna, a szczegółowe strony parametrów i metryk nie dały się odczytać. Nie traktujemy ich niezweryfikowanych progów jako zasad. Przed wdrożeniem connectora trzeba przypiąć i przetestować dostępne pola, parametry atrybucji i ograniczenia wersji API; każdy zaimportowany materiał do Registry otrzymuje własną datę przeglądu i applicability. Dokument nie importuje automatycznie poradników z internetu do wykonywalnej wiedzy agenta.

## Zakres pierwszej wersji dokumentu

Ustalono strukturę czterech źródeł, Registry, Store Profile, Metrics & Evidence Engine, kontekstu, pipeline, historii i ewaluacji. Zaproponowano wersjonowaną ewolucję kontraktu decyzji, sześć Skills Marketing Knowledge Pack i bramki MVP. Mechanika wykonania pozostaje w [02](02-AI-AGENT-ARCHITECTURE.md), przykład operacyjny w [03](03-INVENTORY-GUARDIAN-E2E.md), a prezentacja w [04](04-UI-UX-SPECIFICATION.md#17-uzupełnienie-business-knowledge--decision-intelligence-d-p).
