# ADR-0003: Konwencja modułów backendu

- Status: Proponowany
- Data: 2026-11-14
- Powiązania: plan architektury (B2 „Proponowana mapa modułów”, D1), dokument 02, ADR-0001, backlog B-04

## Kontekst

Backend to modularny monolit: jeden artefakt uruchamiany w profilach `api` i `worker`. Granice modułów
z mapy B2 mają być sprawdzane automatycznie w każdym buildzie, zanim powstanie kod domenowy. Inaczej
zależności zaczną biec w górę (np. `identity` → `commerce`) i trudno to potem odwrócić.

## Decyzja

1. Moduł = bezpośredni podpakiet `com.ecommerceagent` (konwencja Spring Modulith). Moduły:
   `identity`, `integration`, `commerce`, `businessprofile`, `metrics`, `decisions`, `support`,
   `reporting`, `intelligence`, `automation`, `web`.
2. Każdy moduł ma `package-info.java` z `@ApplicationModule(allowedDependencies = …)` zgodnie z B2.
   Lista dozwolonych zależności jest jawna, także pusta (`identity`). Zmiana listy to zmiana architektury
   i wymaga uzasadnienia w PR.
3. Publiczne API modułu to typy w jego pakiecie bazowym. Podpakiety (np. `commerce.internal`) są
   wewnętrzne i niedostępne dla innych modułów (domyślna reguła Spring Modulith).
4. Komunikacja „w górę” mapy (np. `commerce` informuje `decisions`) odbywa się wyłącznie przez zdarzenia
   domenowe, nie przez wywołania.
5. `ModularityTest` (`ApplicationModules.verify()` + lista oczekiwanych modułów) jest częścią
   `./gradlew check` i blokuje merge.
6. Jeden projekt Gradle (`backend/`), bez podziału na podprojekty Gradle: granice pilnuje Modulith,
   a podział buildu dodamy dopiero, gdy pojawi się konkretna potrzeba (np. czas kompilacji).

## Rozważane alternatywy

- **Podprojekty Gradle per moduł** — odrzucone na teraz: twarde granice kompilacji, ale 11 plików
  buildu i rozbudowana konfiguracja bez obecnej korzyści; Modulith daje tę samą kontrolę testem.
- **Moduły tworzone dopiero z pierwszym kodem** — odrzucone: mapa B2 jest zatwierdzona, a puste moduły
  z jawnymi zależnościami kosztują 11 plików `package-info.java` i od pierwszego dnia blokują błędne
  zależności.
- **ArchUnit bez Modulith** — odrzucone: Modulith już jest w stacku (ADR-0001) i daje też zdarzenia,
  testy modułowe i dokumentację.

## Konsekwencje

- Pozytywne: naruszenie mapy modułów kończy się czerwonym buildem z dokładną ścieżką zależności.
- Moduły `support`, `reporting`, `businessprofile` istnieją, choć ich kod powstanie po Pilocie; to tylko
  deklaracje pakietów.
- Wybór warstwy dostępu do danych (Spring Data JDBC vs JPA) nie należy do tego ADR. Backend ma na razie
  tylko `spring-boot-starter-jdbc` i Flyway; decyzja zapadnie w B-06 razem z pierwszymi tabelami.
- Plan wspominał „ADR-002: konwencja modułów”; numer 0002 zajęła granica tool calling, stąd 0003.
  Model tenancy (w planie ADR-003) dostanie kolejny wolny numer w I3.

## Weryfikacja

`backend/`, `./gradlew check`: `ModularityTest` przechodzi (2/2). Sprawdzone mutacyjnie: tymczasowa
klasa w `identity` odwołująca się do `commerce` daje
`Module 'identity' depends on module 'commerce' … Allowed targets: none.` i czerwony build.
