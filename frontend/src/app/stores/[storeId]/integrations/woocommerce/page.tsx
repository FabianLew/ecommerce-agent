import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { Callout } from "@/components/ui/Callout";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { CONNECTION_STATUS_TONE, HEALTH_STATUS_TONE, SYNC_STATUS_TONE, toneOf } from "@/components/ui/tones";
import type { Locale } from "@/i18n/locale";
import type { Messages } from "@/i18n/messages";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

import { checkConnection, startSync } from "../actions";
import { formatInstant, type Integration, syncHelp } from "../labels";
import { RefreshWhileRunning } from "../RefreshWhileRunning";
import { WebhookSetup } from "../WebhookSetup";
import { WooCommerceForm } from "../WooCommerceForm";

export const dynamic = "force-dynamic";

/**
 * WooCommerce connection (plan 4.E, 04 §10): status, connection, webhooks and technical details, or the guided
 * connect flow when there is no connection yet. Secrets are masked and never returned by the backend.
 */
export default async function WooCommercePage({ params }: { params: Promise<{ storeId: string }> }) {
  const { storeId } = await params;
  const path = `${storePath(storeId, "integrations")}/woocommerce`;
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
  const zone = store.data.timezone;
  const canManage = store.data.permissions.includes("STORE_MANAGE");
  const woo = integrations.data.items.find((item) => item.provider === "WOOCOMMERCE");

  return (
    <>
      <header className="integration-header">
        <span className="monogram lg" aria-hidden="true">W</span>
        <div className="integration-heading">
          <PageHeading>{t.providers.WOOCOMMERCE.name}</PageHeading>
          <p className="page-sub">{t.providers.WOOCOMMERCE.description}</p>
        </div>
      </header>

      <div className="integration-detail" data-testid="woocommerce-card">
        {woo ? (
          <>
            <section className="card" aria-labelledby="status-title">
              <div className="card-header">
                <h2 id="status-title">{t.sectionStatus}</h2>
                <StatusBadge tone={toneOf(CONNECTION_STATUS_TONE, woo.status)} data-testid="woocommerce-status">
                  {t.status[woo.status]}
                </StatusBadge>
              </div>
              {woo.lastErrorCode && woo.status === "ERROR" ? (
                <Callout tone="danger" role="alert" data-testid="woocommerce-error">
                  {t.errorHelp[woo.lastErrorCode]}
                </Callout>
              ) : null}
              {woo.warnings.map((warning) => (
                <Callout key={warning} tone="warning" role="note">{t.warningHelp[warning]}</Callout>
              ))}
              <HealthDetails integration={woo} timezone={zone} locale={locale} t={t} />
              <SyncDetails integration={woo} timezone={zone} locale={locale} t={t} />
              {canManage && (woo.allowedActions.includes("START_SYNC") || woo.allowedActions.includes("RUN_CHECK")) ? (
                <div className="form-actions inline-form">
                  {woo.allowedActions.includes("START_SYNC") ? (
                    <form action={startSync.bind(null, storeId, woo.id)}>
                      <button type="submit" className="button primary">{t.importLast14Days}</button>
                    </form>
                  ) : null}
                  {woo.allowedActions.includes("RUN_CHECK") ? (
                    <form action={checkConnection.bind(null, storeId, woo.id)}>
                      <button type="submit" className="button secondary">{t.checkConnection}</button>
                    </form>
                  ) : null}
                </div>
              ) : null}
            </section>

            <section className="card" aria-labelledby="connection-title">
              <h2 id="connection-title">{t.sectionConnection}</h2>
              <dl className="details">
                <dt>{t.storeUrl}</dt>
                <dd>{woo.storeUrl}</dd>
                <dt>{t.apiKey}</dt>
                <dd>{woo.keyHint ? `…${woo.keyHint}` : t.keyNotSaved}</dd>
                <dt>{t.lastCheck}</dt>
                <dd>{formatInstant(woo.lastCheckAt, zone, locale)}</dd>
                <dt>{t.lastSuccess}</dt>
                <dd>{formatInstant(woo.lastSuccessAt, zone, locale)}</dd>
                <dt>{t.sourceCurrency}</dt>
                <dd>{woo.sourceCurrency ?? "—"}</dd>
              </dl>
              {canManage ? (
                <>
                  <h3 className="subheading">{t.changeKey}</h3>
                  <WooCommerceForm storeId={storeId} integrationId={woo.id} />
                </>
              ) : (
                <StatePanel tone="info" title={t.readOnlyTitle} description={t.readOnlyDescription} />
              )}
            </section>

            <section className="card" aria-labelledby="webhooks-title" data-testid="woocommerce-webhooks">
              <div className="card-header">
                <h2 id="webhooks-title">{t.webhooks}</h2>
                <StatusBadge
                  tone={woo.webhook.secretConfigured ? (woo.webhook.lastReceivedAt ? "success" : "info") : "neutral"}
                  role="status"
                  data-testid="webhook-status"
                >
                  {woo.webhook.secretConfigured
                    ? woo.webhook.lastReceivedAt
                      ? t.lastWebhook(formatInstant(woo.webhook.lastReceivedAt, zone, locale))
                      : t.waitingForWebhook
                    : t.webhooksNotConfigured}
                </StatusBadge>
              </div>
              <WebhookSetup
                storeId={storeId}
                integrationId={woo.id}
                webhook={woo.webhook}
                canManage={canManage && woo.allowedActions.includes("SAVE_WEBHOOK_SECRET")}
              />
            </section>

            <details className="card technical-card">
              <summary>{t.sectionTechnical}</summary>
              <dl className="details">
                <dt>{t.connectionId}</dt>
                <dd><code>{woo.id}</code></dd>
                <dt>{t.lastPoll}</dt>
                <dd>{formatInstant(woo.health.lastPollAt, zone, locale)}</dd>
                <dt>{t.missedWebhooks24h}</dt>
                <dd>{woo.health.missedWebhooks24h}</dd>
              </dl>
            </details>
          </>
        ) : canManage ? (
          <section className="card connect-flow" aria-labelledby="connect-title">
            <div className="connect-guide">
              <h2 id="connect-title">{t.guideTitle}</h2>
              <ol className="guide-steps">
                {t.wooGuide.map((step) => (
                  <li key={step}>{step}</li>
                ))}
              </ol>
            </div>
            <div className="connect-form">
              <h2 className="subheading">{t.connectStore}</h2>
              <WooCommerceForm storeId={storeId} />
            </div>
          </section>
        ) : (
          <StatePanel tone="info" title={t.readOnlyTitle} description={t.noConnection} />
        )}
      </div>
    </>
  );
}

type DetailsProps = { integration: Integration; timezone: string; locale: Locale; t: Messages["integrations"] };

function HealthDetails({ integration, timezone, locale, t }: DetailsProps) {
  const health = integration.health;
  return (
    <section className="detail-block" aria-labelledby="health-title" data-testid="woocommerce-health">
      <div className="detail-block-head">
        <h3 id="health-title" className="subheading">{t.health}</h3>
        <StatusBadge tone={toneOf(HEALTH_STATUS_TONE, health.status)} role="status" data-testid="health-status">
          {t.healthStatus[health.status]}
        </StatusBadge>
      </div>
      {health.reasons.map((reason) => (
        <Callout
          key={reason}
          tone={health.status === "STALE" ? "danger" : "warning"}
          role="note"
          data-testid={`health-reason-${reason}`}
        >
          {t.healthReasonHelp[reason]}
        </Callout>
      ))}
      {health.status === "NOT_READY" ? null : (
        <dl className="details">
          <dt>{t.ordersCompleteUntil}</dt>
          <dd>{formatInstant(health.ordersCompleteUntil, timezone, locale)}</dd>
          <dt>{t.lastPoll}</dt>
          <dd>{formatInstant(health.lastPollAt, timezone, locale)}</dd>
        </dl>
      )}
    </section>
  );
}

function SyncDetails({ integration, timezone, locale, t }: DetailsProps) {
  const job = integration.lastSync;
  if (!job) {
    return integration.status === "CONNECTED" ? <p className="muted">{t.notImported}</p> : null;
  }
  const help = syncHelp(job, t);
  return (
    <section className="detail-block" aria-labelledby="sync-title" data-testid="woocommerce-sync">
      <RefreshWhileRunning running={job.status === "RUNNING"} />
      <div className="detail-block-head">
        <h3 id="sync-title" className="subheading">{t.sync}</h3>
        <StatusBadge tone={toneOf(SYNC_STATUS_TONE, job.status)} role="status" data-testid="sync-status">
          {t.syncStatus[job.status]}
        </StatusBadge>
      </div>
      {help ? <Callout tone="danger" role="note">{help}</Callout> : null}
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
