# ADR-0005: Data access through JdbcClient and explicit SQL

- Status: Accepted (owner review, 2026-11-14; tenancy clarification in "Decision")
- Date: 2026-11-14
- Related: ADR-0003 (postponed JDBC/JPA choice), ADR-0004 (store scope in queries), backlog B-06

## Context

The first tables (B-06) require choosing a data access layer. Key requirements: every query must
explicitly filter by `organization_id`/`store_id` (ADR-0004), later work with RLS, inbox/outbox in one
transaction (B-14), uniqueness of the active episode in the database (F1) and predictable analytical queries.

## Decision

Repositories use Spring `JdbcClient` with explicit SQL and manual mapping to Java records. No JPA and no
Spring Data JDBC. The schema lives entirely in Flyway migrations; identifiers are UUIDs assigned in the application,
time is `timestamptz` in UTC, money is `numeric` with a currency or integers in minor units.

JdbcClient is the default choice, not an irreversible decision.

**JdbcClient alone does not provide store isolation.** The query `SELECT * FROM orders WHERE id = :orderId`
reads another store's order if someone supplies its identifier. Therefore:

1. Every repository method that works on a specific store's data takes a `TrustedStoreScope`
   (not a bare `storeId`), and the SQL filters by `organization_id` **and** `store_id` from that object, also for
   primary key lookups (`WHERE id = :id AND store_id = :storeId AND organization_id = :orgId`).
   Writes take `organization_id` and `store_id` from the scope, never from the payload.
2. Repository tests for every store table check that a record of store B is neither visible nor
   modifiable with the scope of store A (read by id, list, update, delete).
3. Composite foreign keys (ADR-0004 item 2) keep the organization and the store consistent in the data itself.
4. Cross-cutting queries without a scope (e.g. a schedule picking stores to monitor) are exceptions:
   they live in named methods with a comment, return only identifiers, and further work goes through a
   scope built in `StoreAccess`.
5. PostgreSQL RLS is added later as an extra layer (ADR-0004 item 9), not instead of the SQL filters.

## Alternatives considered

- **JPA/Hibernate** — rejected: lazy loading, dirty checking and generated queries make it harder to review
  whether every query has a store filter; it fits poorly with upserts, `SKIP LOCKED` and analytical queries.
- **Spring Data JDBC** — considered: simpler than JPA, but identifiers assigned in the application need
  `Persistable`/workarounds for inserts, and scoped queries would be written by hand in `@Query` anyway.
  We will come back to it if manual mapping starts costing more than it gives.
- **jOOQ** — postponed, not rejected: extra code generation from the schema without a current need.
  Typed queries may pay off for complex analytics (metrics, reports). Explicit SQL in migrations
  and repositories does not block moving a module to jOOQ.

## Consequences

- More mapping code; in return every query is visible in the PR review (easy tenancy audit).
- Repository tests run on Testcontainers PostgreSQL, not on H2.
- The decision is easy to narrow locally: a module can move to Spring Data JDBC or jOOQ without affecting
  others, as long as it keeps the tenancy rules above.

## Verification

The `identity` repositories in B-06 and their integration tests (`StoreAccessTest`, `StoreIsolationTest`).
Rules 1–2 apply from the first store data table (B-11/B-13).
