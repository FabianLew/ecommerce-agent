import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import type { Locale } from "@/i18n/locale";
import type { Messages } from "@/i18n/messages";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

import { checkConnection, startSync } from "./actions";
import { formatInstant, type Integration, syncHelp } from "./labels";
import { RefreshWhileRunning } from "./RefreshWhileRunning";
import { WebhookSetup } from "./WebhookSetup";
import { WooCommerceForm } from "./WooCommerceForm";

export const dynamic = "force-dynamic";

/** Integrations (04 §10): WooCommerce card. Secrets are masked and never returned by the backend. */
export default async function IntegrationsPage({ params }: { params: Promise<{ storeId: string }> }) {
  const { storeId } = await params;
  const path = storePath(storeId, "integrations");
  const api = await backend(path);
  const [store, integrations, locale, messages] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/integrations", { params: { path: { storeId } } }),
    getLocale(),
    getMessages(),
  ]);
  const t = messages.integrations;
  redirectIfUnauthenticated(integrations.response.status, path);
  if (!store.data || !integrations.data) {
    notFound();
  }
  const canManage = store.data.permissions.includes("STORE_MANAGE");
  const woo = integrations.data.items.find((item) => item.provider === "WOOCOMMERCE");

  return (
    <>
      <PageHeading>{messages.sections.integrations}</PageHeading>
      <section className="card" aria-labelledby="woocommerce-title" data-testid="woocommerce-card">
        <div className="card-header">
          <h2 id="woocommerce-title">WooCommerce</h2>
          {woo ? (
            <span className={`badge badge-${woo.status.toLowerCase()}`} data-testid="woocommerce-status">
              {t.status[woo.status]}
            </span>
          ) : null}
        </div>
        {woo ? <ConnectionDetails integration={woo} timezone={store.data.timezone} locale={locale} t={t} /> : (
          <p className="muted">{t.noConnection}</p>
        )}
        {woo ? (
          <section className="sync" aria-labelledby="webhooks-title" data-testid="woocommerce-webhooks">
            <h3 id="webhooks-title" className="subheading">{t.webhooks}</h3>
            <p className="badge" role="status" data-testid="webhook-status">
              {woo.webhook.secretConfigured
                ? woo.webhook.lastReceivedAt
                  ? t.lastWebhook(formatInstant(woo.webhook.lastReceivedAt, store.data.timezone, locale))
                  : t.waitingForWebhook
                : t.webhooksNotConfigured}
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
                  <button type="submit" className="button primary">{t.importLast14Days}</button>
                </form>
              ) : null}
              {woo?.allowedActions.includes("RUN_CHECK") ? (
                <form action={checkConnection.bind(null, storeId, woo.id)}>
                  <button type="submit" className="button secondary">{t.checkConnection}</button>
                </form>
              ) : null}
            </div>
            <h3 className="subheading">{woo ? t.changeKey : t.connectStore}</h3>
            <WooCommerceForm storeId={storeId} integrationId={woo?.id} />
          </>
        ) : (
          <StatePanel
            tone="info"
            title={t.readOnlyTitle}
            description={t.readOnlyDescription}
          />
        )}
      </section>
    </>
  );
}

type DetailsProps = { integration: Integration; timezone: string; locale: Locale; t: Messages["integrations"] };

function ConnectionDetails({ integration, timezone, locale, t }: DetailsProps) {
  return (
    <>
      {integration.lastErrorCode && integration.status === "ERROR" ? (
        <p className="form-message error" role="alert" data-testid="woocommerce-error">
          {t.errorHelp[integration.lastErrorCode]}
        </p>
      ) : null}
      {integration.warnings.map((warning) => (
        <p key={warning} className="form-message warning">
          {t.warningHelp[warning]}
        </p>
      ))}
      <dl className="details">
        <dt>{t.storeUrl}</dt>
        <dd>{integration.storeUrl}</dd>
        <dt>{t.apiKey}</dt>
        <dd>{integration.keyHint ? `…${integration.keyHint}` : t.keyNotSaved}</dd>
        <dt>{t.lastCheck}</dt>
        <dd>{formatInstant(integration.lastCheckAt, timezone, locale)}</dd>
        <dt>{t.lastSuccess}</dt>
        <dd>{formatInstant(integration.lastSuccessAt, timezone, locale)}</dd>
        <dt>{t.sourceCurrency}</dt>
        <dd>{integration.sourceCurrency ?? "—"}</dd>
      </dl>
      <HealthDetails integration={integration} timezone={timezone} locale={locale} t={t} />
      <SyncDetails integration={integration} timezone={timezone} locale={locale} t={t} />
    </>
  );
}

function HealthDetails({ integration, timezone, locale, t }: DetailsProps) {
  const health = integration.health;
  return (
    <section className="sync" aria-labelledby="health-title" data-testid="woocommerce-health">
      <h3 id="health-title" className="subheading">{t.health}</h3>
      <p className={`badge badge-health-${health.status.toLowerCase()}`} role="status" data-testid="health-status">
        {t.healthStatus[health.status]}
      </p>
      {health.reasons.map((reason) => (
        <p
          key={reason}
          className={`form-message ${health.status === "STALE" ? "error" : "warning"}`}
          data-testid={`health-reason-${reason}`}
        >
          {t.healthReasonHelp[reason]}
        </p>
      ))}
      {health.status === "NOT_READY" ? null : (
        <dl className="details">
          <dt>{t.lastPoll}</dt>
          <dd>{formatInstant(health.lastPollAt, timezone, locale)}</dd>
          <dt>{t.ordersCompleteUntil}</dt>
          <dd>{formatInstant(health.ordersCompleteUntil, timezone, locale)}</dd>
          <dt>{t.missedWebhooks24h}</dt>
          <dd>{health.missedWebhooks24h}</dd>
        </dl>
      )}
    </section>
  );
}

function SyncDetails({ integration, timezone, locale, t }: DetailsProps) {
  const job = integration.lastSync;
  if (!job) {
    return integration.status === "CONNECTED" ? (
      <p className="muted sync-empty">{t.notImported}</p>
    ) : null;
  }
  const help = syncHelp(job, t);
  return (
    <section className="sync" aria-labelledby="sync-title" data-testid="woocommerce-sync">
      <RefreshWhileRunning running={job.status === "RUNNING"} />
      <h3 id="sync-title" className="subheading">{t.sync}</h3>
      <p className={`badge badge-sync-${job.status.toLowerCase()}`} role="status" data-testid="sync-status">
        {t.syncStatus[job.status]}
      </p>
      {help ? <p className="form-message error">{help}</p> : null}
      <dl className="details">
        <dt>{t.orderWindow}</dt>
        <dd>
          {formatInstant(job.windowFrom, timezone, locale)} – {formatInstant(job.windowUntil, timezone, locale)}
        </dd>
        <dt>{t.productsVariants}</dt>
        <dd>
          {job.productsImported ?? "—"} / {job.variantsImported ?? "—"}
        </dd>
        <dt>{t.orders}</dt>
        <dd data-testid="sync-orders">
          {job.ordersImported ?? "—"}
          {job.ordersExpected != null && job.ordersExpected !== job.ordersImported
            ? t.wooReportsOrders(job.ordersExpected)
            : ""}
        </dd>
        <dt>{t.finished}</dt>
        <dd>{formatInstant(job.finishedAt, timezone, locale)}</dd>
        {integration.history.status !== "WAITING_FOR_IMPORT" ? (
          <>
            <dt>{t.history}</dt>
            <dd data-testid="sync-history">
              {integration.history.status === "COMPLETE"
                ? t.historyComplete(formatInstant(integration.history.importedFrom, timezone, locale))
                : t.historyInProgress(
                    formatInstant(integration.history.importedFrom, timezone, locale),
                    formatInstant(integration.history.target, timezone, locale),
                  )}
            </dd>
          </>
        ) : null}
      </dl>
    </section>
  );
}
