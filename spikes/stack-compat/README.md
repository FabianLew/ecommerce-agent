# Spike: stack-compat (B-02, B-03)

Jednorazowy eksperyment, nie kod produkcyjny. Weryfikuje dwie decyzje:

- **B-02 / [ADR-0001](../../docs/adr/0001-stack-versions.md)** — Spring Boot 4.1.2, Spring AI 2.0.2,
  Spring Modulith 2.1.2 i Temporal 1.40.0 działają razem na Javie 21 (Gradle 9.8.2).
- **B-03 / [ADR-0002](../../docs/adr/0002-spring-ai-tool-calling.md)** — wywołania narzędzi wracają do
  naszego runtime; Spring AI ich nie wykonuje.

## Uruchomienie

```bash
cd spikes/stack-compat
./gradlew test
```

Nie wymaga klucza API, sieci do Anthropic ani serwera Temporal: testy używają in-process test servera
Temporal i lokalnego stuba Messages API (`AnthropicStubServer`). Build testów czyści
`ANTHROPIC_BASE_URL` i `ANTHROPIC_API_KEY`, a testy ustawiają adres bazowy jawnie.

## Zawartość

| Element | Cel |
| --- | --- |
| `inventory`, `decisions`, `workflow` | Trzy minimalne moduły Spring Modulith (weryfikacja granic) |
| `StackCompatibilityTest` | B-02: kontekst, `AnthropicChatModel`, workflow Temporal, `ApplicationModules.verify()` |
| `ToolCallingBoundaryTest` | B-03: granica narzędzi, allowlista, test kontrastowy z `ToolCallingAdvisor` |
| `AnthropicStubServer` | Stub `/v1/messages` z kolejką odpowiedzi i zapisem requestów |

Po przeniesieniu testów granicy narzędzi do backendu (I2/I3) katalog może zostać usunięty.
