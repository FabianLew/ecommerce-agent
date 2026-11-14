# Schematy kontraktów

- `decision-proposal-v1.schema.json`: DecisionProposal v1 (ADR-0012), JSON Schema 2020-12. Backend ma go w
  classpath (`schemas/…`) i sprawdza nim każdą propozycję; ten sam plik dostanie model w F2.
- `examples/`: poprawny przykład dla każdej dyspozycji; testy backendu sprawdzają je schematem i
  deserializacją.

Zmiana znaczenia pola to nowa wersja schematu (nowy plik), nie edycja v1.
