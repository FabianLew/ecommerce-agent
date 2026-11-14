# ADR-0001: Wersje stacku backendu

- Status: Proponowany
- Data: 2026-11-14
- Powiązania: dokument 02 (architektura AI), backlog B-02, ADR-0002, `spikes/stack-compat`

## Kontekst

Backend to modularny monolit Spring Boot z profilami `api` i `worker` (dokument 02, decyzje I2 i I4).
Zanim powstanie kod produkcyjny (I2+), trzeba potwierdzić, że wybrane wersje Spring Boot, Spring AI,
Spring Modulith i Temporal SDK współpracują ze sobą: rozwiązują się z jednego zestawu BOM-ów,
startują w jednym kontekście i dają się testować bez zewnętrznej infrastruktury.

Ograniczenia:

- Używamy wyłącznie wydań GA. Wersje RC/M nie wchodzą do projektu.
- Nie przyjmujemy wydań młodszych niż ok. 2 tygodnie (ryzyko szybkich poprawek i regresji).
- Build: Gradle Kotlin DSL (decyzja O1).

## Decyzja

| Składnik | Wersja | Uwagi |
| --- | --- | --- |
| Java (toolchain) | 21 (LTS) | Patrz „Konsekwencje”: ocena Javy 25 osobno |
| Gradle (wrapper) | 9.8.2 | 9.9.x odrzucone jako zbyt świeże |
| Spring Boot | 4.1.2 | Najnowsze GA; 4.2 jest w fazie RC |
| Spring AI | 2.0.2 | Najnowsze GA linii 2.0; 2.1 w fazie RC |
| Spring Modulith | 2.1.2 | Zgodne z Boot 4.1 |
| Temporal Java SDK + `temporal-spring-boot-starter` | 1.40.0 | Działa z Boot 4.1.2 (patrz niżej) |

Wersje są zarządzane przez BOM-y (`spring-boot-dependencies`, `spring-ai-bom`, `spring-modulith-bom`);
Temporal ma jawnie przypiętą wersję. Klient Anthropic w Spring AI 2.0 to oficjalny SDK
`com.anthropic:anthropic-java-core` (2.72.0 przez BOM Spring AI).

## Rozważane alternatywy

- **Spring Boot 4.2 RC / Spring AI 2.1 RC** — odrzucone: wersje przedprodukcyjne.
- **Spring Boot 3.5 + Spring AI 1.x** — odrzucone: start nowego projektu na linii, która wcześniej
  traci wsparcie, oznacza pewną migrację w trakcie MVP; Spring AI 1.x ma inny model wykonywania
  narzędzi niż 2.0 (ADR-0002), więc migracja dotknęłaby najbardziej wrażliwej części runtime.
- **Java 25 (LTS)** — nie odrzucona merytorycznie; w środowisku, w którym powstał spike, JDK 25 nie było
  dostępne do pobrania, więc nie mogliśmy jej zweryfikować. Decyzję zostawiamy otwartą (patrz niżej).
- **Gradle 9.9.x** — odrzucone na teraz: wydanie młodsze niż 2 tygodnie.

## Konsekwencje

- Pozytywne: jeden spójny zestaw wersji GA, testowalny lokalnie bez sieci (Temporal in-process test
  server, stub API Anthropic).
- `temporal-spring-boot-starter` 1.40.0 deklaruje w swoim POM import BOM-u Spring Boot 2.7.18. W Gradle
  z platformą Boot 4.1.2 zależności rozwiązują się do wersji z Boot 4.1.2 i starter działa (test
  przechodzi), ale to ryzyko przy aktualizacjach: po każdej zmianie wersji Temporal lub Boot uruchamiamy
  spike/testy integracyjne workera.
- Spring AI 2.0 zmienił API wykonywania narzędzi względem 1.x (usunięte `internalToolExecutionEnabled`);
  konsekwencje opisuje ADR-0002.
- Oficjalny SDK Anthropic czyta zmienną środowiskową `ANTHROPIC_BASE_URL`. Każda konfiguracja
  (testy, środowiska) musi ustawiać `spring.ai.anthropic.base-url` jawnie; build testów czyści
  `ANTHROPIC_BASE_URL` i `ANTHROPIC_API_KEY`.
- Do zrobienia: ocenić Javę 25 w CI (osobne zadanie, bez blokowania I2). Zmiana toolchaina to jedna linia,
  więc decyzja jest łatwa do odwrócenia.
- Do zrobienia: ustawić Renovate/Dependabot z regułą „GA, min. 14 dni” przy tworzeniu backendu (I2).

## Weryfikacja

Spike `spikes/stack-compat`, `./gradlew test` (Java 21.0.12, Gradle 9.8.2) — `StackCompatibilityTest`, 4/4:

- `springAiAnthropicChatModelIsAutoConfigured` — kontekst startuje, `ChatModel` to `AnthropicChatModel`.
- `temporalWorkflowRunsThroughAutoDiscoveredWorkerAndActivity` — workflow i aktywność wykryte
  automatycznie, wykonane na in-process test serverze Temporal.
- `springModulithVerifiesModuleBoundaries` — `ApplicationModules.verify()` przechodzi dla trzech modułów.
- `crossModuleBeanWiringWorks` — wstrzykiwanie między modułami przez publiczne API modułu.
