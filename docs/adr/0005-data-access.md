# ADR-0005: Dostęp do danych przez JdbcClient i jawny SQL

- Status: Proponowany
- Data: 2026-11-14
- Powiązania: ADR-0003 (odłożony wybór JDBC/JPA), ADR-0004 (zakres sklepu w zapytaniach), backlog B-06

## Kontekst

Pierwsze tabele (B-06) wymagają wyboru warstwy dostępu do danych. Kluczowe wymagania: każde zapytanie musi
jawnie filtrować po `organization_id`/`store_id` (ADR-0004), później działać z RLS, inbox/outbox w jednej
transakcji (B-14), unikalność aktywnego epizodu w bazie (F1) i przewidywalne zapytania analityczne.

## Decyzja

Repozytoria używają Spring `JdbcClient` z jawnym SQL i ręcznym mapowaniem na rekordy Java. Bez JPA i bez
Spring Data JDBC. Schemat jest w pełni w migracjach Flyway; identyfikatory to UUID nadawane w aplikacji,
czas jako `timestamptz` w UTC, pieniądze jako `numeric` z walutą lub liczby całkowite w jednostkach
najmniejszych.

## Rozważane alternatywy

- **JPA/Hibernate** — odrzucone: lazy loading, dirty checking i generowane zapytania utrudniają przegląd,
  czy każde zapytanie ma filtr sklepu; słabo pasuje do upsertów, `SKIP LOCKED` i zapytań analitycznych.
- **Spring Data JDBC** — rozważane: prostsze niż JPA, ale identyfikatory nadawane w aplikacji wymagają
  `Persistable`/obejść dla insertów, a zapytania ze scope i tak byłyby pisane ręcznie przez `@Query`.
  Wrócimy do niego, jeśli ręczne mapowanie zacznie kosztować więcej niż daje.
- **jOOQ** — odrzucone na teraz: dodatkowe generowanie kodu ze schematu bez obecnej potrzeby.

## Konsekwencje

- Więcej kodu mapującego; w zamian każde zapytanie jest widoczne w przeglądzie PR (łatwy audyt tenancy).
- Testy repozytoriów działają na Testcontainers PostgreSQL, nie na H2.
- Decyzja łatwa do zawężenia lokalnie: moduł może przejść na Spring Data JDBC bez wpływu na inne.

## Weryfikacja

Repozytoria `identity` w B-06 i ich testy integracyjne.
