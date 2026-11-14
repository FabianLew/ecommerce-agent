# ADR-0005: Dostęp do danych przez JdbcClient i jawny SQL

- Status: Zaakceptowany (review właściciela, 2026-11-14; doprecyzowanie tenancy w „Decyzji”)
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

JdbcClient to domyślny wybór, a nie decyzja nieodwracalna.

**Sam JdbcClient nie zapewnia izolacji sklepów.** Zapytanie `SELECT * FROM orders WHERE id = :orderId`
przeczyta zamówienie innego sklepu, jeśli ktoś poda jego identyfikator. Dlatego:

1. Każda metoda repozytorium, która operuje na danych konkretnego sklepu, przyjmuje `TrustedStoreScope`
   (nie gołe `storeId`), a SQL filtruje po `organization_id` **i** `store_id` z tego obiektu, także przy
   wyszukiwaniu po kluczu głównym (`WHERE id = :id AND store_id = :storeId AND organization_id = :orgId`).
   Zapisy biorą `organization_id` i `store_id` z zakresu, nigdy z payloadu.
2. Testy repozytorium każdej tabeli sklepu sprawdzają, że rekord sklepu B nie jest widoczny ani
   modyfikowalny z zakresem sklepu A (odczyt po id, lista, update, delete).
3. Złożone klucze obce (ADR-0004 p. 2) pilnują spójności organizacji i sklepu w samych danych.
4. Zapytania przekrojowe bez zakresu (np. harmonogram wybierający sklepy do monitorowania) są wyjątkami:
   żyją w nazwanych metodach z komentarzem, zwracają wyłącznie identyfikatory, a dalsza praca idzie przez
   zakres zbudowany w `StoreAccess`.
5. Docelowo dochodzi RLS PostgreSQL jako dodatkowa warstwa (ADR-0004 p. 9), nie zamiast filtrów w SQL.

## Rozważane alternatywy

- **JPA/Hibernate** — odrzucone: lazy loading, dirty checking i generowane zapytania utrudniają przegląd,
  czy każde zapytanie ma filtr sklepu; słabo pasuje do upsertów, `SKIP LOCKED` i zapytań analitycznych.
- **Spring Data JDBC** — rozważane: prostsze niż JPA, ale identyfikatory nadawane w aplikacji wymagają
  `Persistable`/obejść dla insertów, a zapytania ze scope i tak byłyby pisane ręcznie przez `@Query`.
  Wrócimy do niego, jeśli ręczne mapowanie zacznie kosztować więcej niż daje.
- **jOOQ** — odłożone, nie odrzucone: dodatkowe generowanie kodu ze schematu bez obecnej potrzeby.
  Typowane zapytania mogą się opłacić przy złożonej analityce (metryki, raporty). Jawny SQL w migracjach
  i repozytoriach nie blokuje przejścia modułu na jOOQ.

## Konsekwencje

- Więcej kodu mapującego; w zamian każde zapytanie jest widoczne w przeglądzie PR (łatwy audyt tenancy).
- Testy repozytoriów działają na Testcontainers PostgreSQL, nie na H2.
- Decyzja łatwa do zawężenia lokalnie: moduł może przejść na Spring Data JDBC lub jOOQ bez wpływu na
  inne, o ile zachowa zasady tenancy powyżej.

## Weryfikacja

Repozytoria `identity` w B-06 i ich testy integracyjne (`StoreAccessTest`, `StoreIsolationTest`).
Zasady 1–2 obowiązują od pierwszej tabeli danych sklepu (B-11/B-13).
