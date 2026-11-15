# infra/observability: alerts and dashboard (ADR-0024)

- `alerts.yml`: Prometheus alert rules; each links its section in
  [docs/operations/runbooks.md](../../docs/operations/runbooks.md). Check with
  `docker run --rm -v "$PWD":/w --entrypoint promtool prom/prometheus:v3.5.0 check rules /w/alerts.yml`.
- `dashboard-pilot.json`: Grafana dashboard with a `datasource` variable for the Prometheus data source.

The backend exports metrics over OTLP in profile `cloud` (`OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`). The rule names
assume the usual OTLP-to-Prometheus translation: dots become underscores, counters end in `_total`, values in
seconds end in `_seconds`. Gauges come from the worker only.
