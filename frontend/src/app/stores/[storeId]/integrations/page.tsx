import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

import { checkConnection, startSync } from "./actions";
import {
  ERROR_HELP,
  formatInstant,
  HEALTH_LABELS,
  HEALTH_REASON_HELP,
  type Integration,
  STATUS_LABELS,
  SYNC_STATUS_LABELS,
  syncHelp,
  WARNING_HELP,
} from "./labels";
import { RefreshWhileRunning } from "./RefreshWhileRunning";
import { WebhookSetup } from "./WebhookSetup";
import { WooCommerceForm } from "./WooCommerceForm";

export const dynamic = "force-dynamic";

/** Integrations (04 §10): WooCommerce card. Secrets are masked and never returned by the backend. */
export default async function IntegrationsPage({ params }: { params: Promise<{ storeId: string }> }) {
  const { storeId } = await params;
  const path = storePath(storeId, "integrations");
  const api = await backend(path);
  const [store, integrations] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/integrations", { params: { path: { storeId } } }),
  ]);
  redirectIfUnauthenticated(integrations.response.status, path);
  if (!store.data || !integrations.data) {
    notFound();
  }
  const canManage = store.data.permissions.includes("STORE_MANAGE");
  const woo = integrations.data.items.find((item) => item.provider === "WOOCOMMERCE");

  return (
    <>
      <PageHeading>Integracje</PageHeading>
      <section className="card" aria-labelledby="woocommerce-title" data-testid="woocommerce-card">
        <div className="card-header">
          <h2 id="woocommerce-title">WooCommerce</h2>
          {woo ? (
            <span className={`badge badge-${woo.status.toLowerCase()}`} data-testid="woocommerce-status">
              {STATUS_LABELS[woo.status]}
            </span>
          ) : null}
        </div>
        {woo ? <ConnectionDetails integration={woo} timezone={store.data.timezone} /> : (
          <p className="muted">Sklep nie ma jeszcze połączenia. Podłącz WooCommerce kluczem REST API tylko do odczytu.</p>
        )}
        {woo ? (
          <section className="sync" aria-labelledby="webhooks-title" data-testid="woocommerce-webhooks">
            <h3 id="webhooks-title" className="subheading">Webhooki</h3>
            <p className="badge" role="status" data-testid="webhook-status">
              {woo.webhook.secretConfigured
                ? woo.webhook.lastReceivedAt
                  ? `Ostatni webhook: ${formatInstant(woo.webhook.lastReceivedAt, store.data.timezone)}`
                  : "Sekret zapisany, czekamy na pierwszy webhook"
                : "Nie skonfigurowano"}
            </p>
            <WebhookSetup
              storeId={storeId}
              integrationId={woo.id}
              webhook={woo.webhook}
              canManage={canManage && woo.allowedActions.includes("SAVE_WEBHOOK_SECRET")}
            />
          </section>
        ) : null}
        {canManage ? (
          <>
            <div className="form-actions inline-form">
              {woo?.allowedActions.includes("START_SYNC") ? (
                <form action={startSync.bind(null, storeId, woo.id)}>
                  <button type="submit" className="button primary">Importuj dane z ostatnich 14 dni</button>
                </form>
              ) : null}
              {woo?.allowedActions.includes("RUN_CHECK") ? (
                <form action={checkConnection.bind(null, storeId, woo.id)}>
                  <button type="submit" className="button secondary">Sprawdź połączenie</button>
                </form>
              ) : null}
            </div>
            <h3 className="subheading">{woo ? "Zmień klucz API" : "Połącz sklep"}</h3>
            <WooCommerceForm storeId={storeId} integrationId={woo?.id} />
          </>
        ) : (
          <StatePanel
            tone="info"
            title="Tylko podgląd"
            description="Połączenia może zmieniać właściciel sklepu."
          />
        )}
      </section>
    </>
  );
}

function ConnectionDetails({ integration, timezone }: { integration: Integration; timezone: string }) {
  return (
    <>
      {integration.lastErrorCode && integration.status === "ERROR" ? (
        <p className="form-message error" role="alert" data-testid="woocommerce-error">
          {ERROR_HELP[integration.lastErrorCode]}
        </p>
      ) : null}
      {integration.warnings.map((warning) => (
        <p key={warning} className="form-message warning">
          {WARNING_HELP[warning]}
        </p>
      ))}
      <dl className="details">
        <dt>Adres sklepu</dt>
        <dd>{integration.storeUrl}</dd>
        <dt>Klucz API</dt>
        <dd>{integration.keyHint ? `…${integration.keyHint}` : "nie zapisano"}</dd>
        <dt>Ostatni test</dt>
        <dd>{formatInstant(integration.lastCheckAt, timezone)}</dd>
        <dt>Ostatnie udane połączenie</dt>
        <dd>{formatInstant(integration.lastSuccessAt, timezone)}</dd>
        <dt>Waluta w WooCommerce</dt>
        <dd>{integration.sourceCurrency ?? "—"}</dd>
      </dl>
      <HealthDetails integration={integration} timezone={timezone} />
      <SyncDetails integration={integration} timezone={timezone} />
    </>
  );
}

function HealthDetails({ integration, timezone }: { integration: Integration; timezone: string }) {
  const health = integration.health;
  return (
    <section className="sync" aria-labelledby="health-title" data-testid="woocommerce-health">
      <h3 id="health-title" className="subheading">Stan integracji</h3>
      <p className={`badge badge-health-${health.status.toLowerCase()}`} role="status" data-testid="health-status">
        {HEALTH_LABELS[health.status]}
      </p>
      {health.reasons.map((reason) => (
        <p
          key={reason}
          className={`form-message ${health.status === "STALE" ? "error" : "warning"}`}
          data-testid={`health-reason-${reason}`}
        >
          {HEALTH_REASON_HELP[reason]}
        </p>
      ))}
      {health.status === "NOT_READY" ? null : (
        <dl className="details">
          <dt>Ostatni odczyt zmian</dt>
          <dd>{formatInstant(health.lastPollAt, timezone)}</dd>
          <dt>Zamówienia kompletne do</dt>
          <dd>{formatInstant(health.ordersCompleteUntil, timezone)}</dd>
          <dt>Zgubione webhooki (24 h)</dt>
          <dd>{health.missedWebhooks24h}</dd>
        </dl>
      )}
    </section>
  );
}

function SyncDetails({ integration, timezone }: { integration: Integration; timezone: string }) {
  const job = integration.lastSync;
  if (!job) {
    return integration.status === "CONNECTED" ? (
      <p className="muted sync-empty">Dane nie zostały jeszcze zaimportowane.</p>
    ) : null;
  }
  const help = syncHelp(job);
  return (
    <section className="sync" aria-labelledby="sync-title" data-testid="woocommerce-sync">
      <RefreshWhileRunning running={job.status === "RUNNING"} />
      <h3 id="sync-title" className="subheading">Synchronizacja</h3>
      <p className={`badge badge-sync-${job.status.toLowerCase()}`} role="status" data-testid="sync-status">
        {SYNC_STATUS_LABELS[job.status]}
      </p>
      {help ? <p className="form-message error">{help}</p> : null}
      <dl className="details">
        <dt>Zakres zamówień</dt>
        <dd>
          {formatInstant(job.windowFrom, timezone)} – {formatInstant(job.windowUntil, timezone)}
        </dd>
        <dt>Produkty / warianty</dt>
        <dd>
          {job.productsImported ?? "—"} / {job.variantsImported ?? "—"}
        </dd>
        <dt>Zamówienia</dt>
        <dd data-testid="sync-orders">
          {job.ordersImported ?? "—"}
          {job.ordersExpected != null && job.ordersExpected !== job.ordersImported
            ? ` (WooCommerce podaje ${job.ordersExpected})`
            : ""}
        </dd>
        <dt>Zakończono</dt>
        <dd>{formatInstant(job.finishedAt, timezone)}</dd>
      </dl>
    </section>
  );
}
