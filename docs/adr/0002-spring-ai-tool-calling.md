# ADR-0002: Pętla narzędzi należy do runtime, nie do Spring AI

- Status: Proponowany
- Data: 2026-11-14
- Powiązania: dokument 02 (sekcje 3–4: Agent Runtime, Tool Registry), dokument 03 (IG-11), backlog B-03,
  ADR-0001, `spikes/stack-compat`

## Kontekst

Model LLM tylko proponuje. Każde wywołanie narzędzia musi przejść przez nasz runtime: allowlistę narzędzi
READ, walidację argumentów, `TrustedStoreScope`, limity kroków i tokenów, audyt (dokument 02). Jeśli
biblioteka sama wykona narzędzie i ponownie zapyta model, te kontrole zostają ominięte, a my tracimy
zapis przebiegu.

W planie zakładaliśmy, że wystarczy `internalToolExecutionEnabled(false)` w opcjach czatu. Spike B-03
pokazał, że w Spring AI 2.0.2 tej opcji już nie ma, a model wykonywania narzędzi wygląda inaczej:

- `ChatModel.call()` (np. `AnthropicChatModel`) **nie wykonuje narzędzi**. Zwraca odpowiedź z
  `toolCalls`; `ToolCallingManager` służy tam tylko do zbudowania definicji narzędzi.
- Pętlę wykonuje `ToolCallingAdvisor` w `ChatClient`. Autokonfiguracja rejestruje go automatycznie,
  gdy w wywołaniu są narzędzia (`spring.ai.chat.client.tool-calling.enabled`, domyślnie `true`).
  Można go też włączyć per wywołanie (`AdvisorParams.toolCallingAdvisorAutoRegister(true)`).

## Decyzja

1. Agent Runtime rozmawia z modelem przez **`ChatModel` bezpośrednio** (za naszym `ModelGateway`),
   nie przez `ChatClient`. Pętla narzędzi (wywołanie modelu → dispatch narzędzia przez nasz
   `ToolDispatcher` → `ToolResponseMessage` → kolejne wywołanie) jest kodem runtime.
2. Obrona w głąb: w konfiguracji aplikacji zawsze `spring.ai.chat.client.tool-calling.enabled=false`,
   żeby `ChatClient` wzięty z kontekstu też nie wykonywał narzędzi.
3. Do modelu przekazujemy tylko definicje narzędzi READ z allowlisty. Wywołanie narzędzia spoza
   allowlisty kończy się ustrukturyzowanym błędem `TOOL_NOT_ALLOWED` odesłanym do modelu, bez wykonania.
4. `ToolCallback` w opcjach służy wyłącznie do opisu narzędzia (nazwa, opis, schemat wejścia). Logika
   narzędzi żyje w runtime, nie w callbackach.
5. Adres bazowy API ustawiamy zawsze jawnie (`spring.ai.anthropic.base-url`), bo SDK czyta
   `ANTHROPIC_BASE_URL` ze środowiska (ADR-0001).
6. Każdy upgrade Spring AI wymaga przejścia testów granicy narzędzi (poniżej) — należą one do testów
   bezpieczeństwa runtime, które blokują merge.

## Rozważane alternatywy

- **`ChatClient` + `ToolCallingAdvisor` z własnym `ToolCallingManager`** (wstrzyknięcie naszych kontroli
  w pętlę Spring AI) — odrzucone: pętla, limity i stan przebiegu byłyby rozproszone między nasz kod a
  advisor chain; trudniej to audytować i trudniej zagwarantować kolejność kontroli. Wraca do rozważenia
  tylko, jeśli pojawi się konkretna potrzeba (np. streaming z narzędziami).
- **Bezpośrednio oficjalny SDK Anthropic, bez Spring AI** — odrzucone na teraz: decyzja I2 to Spring AI;
  przy `ChatModel` bez `ChatClient` Spring AI daje nam mapowanie wiadomości, obserwowalność i
  konfigurację, a nie przejmuje kontroli nad narzędziami. Granica `ModelGateway` pozwala zmienić to później.
- **Poleganie na domyślnym zachowaniu bez testów** — odrzucone: API zmieniło się już między 1.x a 2.0.

## Konsekwencje

- Pozytywne: jedna, jawna pętla narzędzi w runtime; każda kontrola i każdy krok jest w naszym kodzie
  i w ledgerze/audycie.
- Negatywne: piszemy własną pętlę (kilkadziesiąt linii plus limity), zamiast użyć gotowej.
- Ryzyko: kolejne wersje Spring AI mogą znów przenieść wykonywanie narzędzi. Mitygacja: testy niżej
  w zestawie bezpieczeństwa runtime (przeniesione z spike'a do backendu w I2/I3).
- `CLAUDE.md` odsyła do tego ADR w sprawie sposobu wymuszenia reguły.

## Weryfikacja

Spike `spikes/stack-compat`, `ToolCallingBoundaryTest`, 5/5. Testy używają autokonfigurowanego
`AnthropicChatModel` i lokalnego stuba Messages API (bez klucza i sieci):

- `toolCallIsReturnedToCallerAndNotExecutedBySpringAi` — `ChatModel.call()` zwraca `tool_use`,
  callback nie jest wykonany, do API poszedł 1 request z definicją narzędzia.
- `runtimeDrivesTheLoopAndSendsToolResultBack` — nasz dispatcher wykonuje narzędzie, `tool_result`
  z `tool_use_id` i dowodami trafia w drugim requeście; Spring AI wykonał 0 narzędzi.
- `toolOutsideAllowlistIsNeverExecuted` — model prosi o `pause_ad` (IG-11): `TOOL_NOT_ALLOWED`, 0 wykonań.
- `autoConfiguredChatClientDoesNotExecuteTools` — przy `tool-calling.enabled=false` `ChatClient` z
  kontekstu zwraca `tool_use` bez wykonania. Sprawdzone mutacyjnie: po zmianie na `true` test nie przechodzi.
- `chatClientWithToolCallingAdvisorWouldRunToolsInsideSpringAi` — test kontrastowy dokumentujący
  zagrożenie: z `ToolCallingAdvisor` jedno wywołanie wykonuje narzędzie i samo robi drugi request.
