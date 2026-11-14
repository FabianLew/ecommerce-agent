# Contract schemas

- `decision-proposal-v1.schema.json`: DecisionProposal v1 (ADR-0012), JSON Schema 2020-12. The backend has it on
  the classpath (`schemas/…`) and checks every proposal against it; the model gets the same file in F2.
- `examples/`: a valid example for every disposition; backend tests check them against the schema and through
  deserialization.

A change in the meaning of a field is a new schema version (a new file), never an edit of v1.
