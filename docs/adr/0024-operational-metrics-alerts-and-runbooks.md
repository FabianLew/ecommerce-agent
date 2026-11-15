# ADR-0024: Operational metrics, alerts and runbooks

- Status: Proposed
- Date: 2026-11-15
- Related: ADR-0020 (ledger), ADR-0021 (notifications, outcomes), ADR-0016 (agent runs), ADR-0023 (OTLP export
  in profile `cloud`), epic E-47

## Context

The pilot runs actions on a real store. An unconfirmed pause, a stuck worker, a webhook backlog or a mail provider
that stopped sending must reach a person before the store owner notices. Logs and traces exist (JSON logs,
OpenTelemetry), but nothing measures the states that need a person, and there are no alert rules or procedures.
The metrics backend is chosen with the hosting (ADR-0023), so the definitions must not depend on it.

## Decision

1. **Metrics through Micrometer, exported over OTLP** (profile `cloud`). Names are part of the alert contract:
   - Counters, per process: `operator.action.outcomes{result, authorship}` (when an outcome is recorded),
     `operator.notification.deliveries{status, code}` (each delivery attempt), `operator.agent.runs{status}` and the
     summary `operator.agent.cost.usd` (each finished run).
   - Gauges, read from the database by the worker only (so replicas of the API do not duplicate them):
     `operator.executions.unknown`, `operator.executions.stuck`, `operator.mutation.stops.active`,
     `operator.observations.overdue`, `operator.webhook.inbox.pending`, `operator.webhook.inbox.oldest.age`
     (seconds), `operator.outbox.pending`, `operator.outbox.oldest.age` (seconds), `operator.connections.failing`,
     `operator.notification.deliveries.exhausted`. Each is one count over all stores; store identifiers are never
     metric labels (cardinality, and they belong in logs and the audit).
2. **Alerts as code** in `infra/observability/alerts.yml` (Prometheus rule format, checked with `promtool`), each
   with a severity and a runbook link. Critical: an `UNKNOWN` execution, API 5xx above 2%, no worker. The dashboard
   `infra/observability/dashboard-pilot.json` (Grafana) shows the same series.
3. **Runbooks** in `docs/operations/runbooks.md`: one section per alert, plus the restore drill, key rotation,
   enabling retention and the response to a wrong pause. The first response to anything touching ads is the
   mutation stop.

## Alternatives considered

- **Gauges updated by the code paths.** They drift after restarts and between replicas; a count over the table is
  the truth and costs a few indexed queries per export interval.
- **Per-store labels.** Easier per-store dashboards, but unbounded cardinality and store identifiers in a third
  system; the application's Activity list is the per-store view.

## Consequences

- Wiring the rules and the dashboard into the chosen backend (or a hosted Grafana) comes with the hosting; the Prometheus
  names assume the usual OTLP translation (dots to underscores, `_total`, `_seconds`).
- Alert routing (who is paged, when) is decided with the pilot procedure (E-48).

## Verification

- The execution workflow test checks the outcome counter, the delivery counter and that the worker's gauges are
  readable.
- `promtool check rules` accepts `alerts.yml`.
