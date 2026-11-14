# ecommerce-agent

Autonomous E-commerce Operator — a background operator for existing DTC stores (WooCommerce + Meta Ads +
customer-service e-mail). It detects problems, prepares decisions with evidence, performs allowed actions and
verifies their effect.

> Status: early implementation (iteration I7): backend with tenancy, observability and a Temporal worker;
> WooCommerce import, webhooks, polling and integration health; Metrics Engine v1; the first Inventory Guardian
> episode in shadow mode (risk detection, evidence, DecisionProposal v1, baseline and policy evaluation); frontend
> with sign-in and store selection; local environment and CI.

## Repository layout

| Path | Contents | Since iteration |
| --- | --- | --- |
| `docs/architecture/` | Documents 01–05: product, AI architecture, Inventory Guardian E2E, UI/UX, Decision Intelligence | I1 |
| `docs/adr/` | Architecture Decision Records | I1 |
| `contracts/openapi/` | Backend API contract, source of the generated frontend client | I4 |
| `spikes/` | One-off experiments that verify technical decisions (not production code) | I1 (B-02, B-03) |
| `backend/` | Spring Boot modular monolith (profiles `api`, `worker`) | I2 |
| `frontend/` | Next.js (App Router, TypeScript) | I4 |
| `knowledge/` | Skills, playbooks, contract JSON Schemas, evaluation cases | I7 |
| `infra/` | Docker Compose for the local environment | I2 |
| `.github/workflows/` | CI: backend, runtime safety tests, environment smoke test | I2 |

## Quick start

```bash
docker compose -f infra/docker-compose.yml up -d --wait   # local environment, details: infra/README.md
cd backend && ./gradlew check                            # needs JDK 21 and Docker
cd frontend && pnpm install && pnpm test                 # needs Node 22; details: frontend/README.md
```

## Documentation

The documents in `docs/architecture/` are the source of truth about the product and the architecture (reading
order: 01 → 02 → 03 → 04 → 05). Decisions made during implementation that refine or change those documents are
recorded as ADRs in `docs/adr/`.

## Iteration I1 spikes

- [`spikes/stack-compat`](spikes/stack-compat/README.md) — B-02 (stack version compatibility, ADR-0001)
  and B-03 (the tool-calling boundary in Spring AI, ADR-0002).
