# Instrukcje dla Claude Code w tym repozytorium

## Kontekst

- Produkt i architektura: `docs/architecture/01–05`. To źródło prawdy; nie traktuj propozycji [P] z dokumentów
  jako zatwierdzonych, jeśli ADR mówi inaczej.
- Decyzje implementacyjne: `docs/adr/`. Każda decyzja trudna do odwrócenia wymaga ADR przed kodem.

## Zasady pracy (ustalone z właścicielem projektu)

- Komunikacja z właścicielem po polsku; kod, nazwy klas i komentarze w kodzie po angielsku.
- Praca w małych iteracjach. Każda iteracja kończy się PR, raportem (zakres, wyniki testów, decyzje, propozycja
  kolejnej iteracji) i czeka na zatwierdzenie. Nie przechodź automatycznie do następnej iteracji.
- Jeden PR = jedno zadanie z backlogu lub mały, spójny zestaw. Opis PR zawiera: co i dlaczego, jak testowano, ryzyka.
- Nie dodawaj generycznych frameworków „na zapas”. Abstrakcja powstaje, gdy wymaga jej konkretny proces.

## Niezmienne reguły architektury

- Model LLM tylko proponuje (`DecisionProposal`). Backend waliduje, ocenia politykę, wystawia `AuthorizedAction`
  i wykonuje przez executor z ledgerem. Model widzi wyłącznie narzędzia typu READ.
- Spring AI nie może wykonywać narzędzi samodzielnie: pętla narzędzi należy do naszego runtime (ADR-0002).
  Runtime woła `ChatModel` bezpośrednio (nie `ChatClient`), a konfiguracja zawsze ma
  `spring.ai.chat.client.tool-calling.enabled=false`.
- Tenant/sklep pochodzi z zaufanego `TrustedStoreScope`, nigdy z argumentów modelu ani payloadu.
  Zakres buduje tylko `StoreAccess` (użytkownik albo aktor systemowy); metody repozytoriów dla danych sklepu
  przyjmują `TrustedStoreScope` i filtrują po `organization_id` i `store_id`; tabele sklepu mają złożony FK
  do `store (id, organization_id)`; wrażliwa akcja woła `StoreAccess.reauthorize` tuż przed wykonaniem
  (ADR-0004, ADR-0005).
- Testy bezpieczeństwa runtime muszą przechodzić w 100% i blokują merge. Jakość rekomendacji LLM oceniamy
  osobno (scenariusze eksperckie, porównanie z deterministycznym baseline) jako raport, bez wymogu zgodności.
- Pieniądze jako `BigDecimal` lub liczby całkowite w jednostkach najmniejszych z walutą; czas w UTC.

## Uwaga środowiskowa

Zmienna `ANTHROPIC_BASE_URL` może być ustawiona w środowisku narzędzi deweloperskich. Testy integracji z modelem
muszą jawnie ustawiać adres bazowy, żeby nie trafiać do nieoczekiwanego endpointu.
