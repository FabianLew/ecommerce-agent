import Link from "next/link";
import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { Callout } from "@/components/ui/Callout";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { CONNECTION_STATUS_TONE, toneOf } from "@/components/ui/tones";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

import { formatInstant } from "../labels";
import { checkMeta } from "../meta-actions";
import { MetaForm } from "../MetaForm";

export const dynamic = "force-dynamic";

/**
 * Meta Ads connection (plan 4.E, ADR-0017): account, token hint, write permission, the last ad read and the ad
 * mappings, or the guided connect flow. The token is never returned by the backend.
 */
export default async function MetaAdsPage({ params }: { params: Promise<{ storeId: string }> }) {
  const { storeId } = await params;
  const integrations = storePath(storeId, "integrations");
  const path = `${integrations}/meta-ads`;
  const api = await backend(path);
  const [store, envelope, locale, messages] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/meta-connection", { params: { path: { storeId } } }),
    getLocale(),
    getMessages(),
  ]);
  redirectIfUnauthenticated(envelope.response.status, path);
  if (!store.data || !envelope.data) {
    notFound();
  }
  const t = messages.meta;
  const ti = messages.integrations;
  const zone = store.data.timezone;
  const connection = envelope.data.connection;
  const canManage = envelope.data.canCreate;

  return (
    <>
      <header className="integration-header">
        <span className="monogram lg" aria-hidden="true">M</span>
        <div className="integration-heading">
          <PageHeading>{ti.providers.META_ADS.name}</PageHeading>
          <p className="page-sub">{ti.providers.META_ADS.description}</p>
        </div>
      </header>

      <div className="integration-detail" data-testid="meta-card">
        {connection ? (
          <>
            <section className="card" aria-labelledby="status-title">
              <div className="card-header">
                <h2 id="status-title">{ti.sectionStatus}</h2>
                <StatusBadge tone={toneOf(CONNECTION_STATUS_TONE, connection.status)} data-testid="meta-status">
                  {t.status[connection.status]}
                </StatusBadge>
              </div>
              {connection.status === "ERROR" && connection.lastErrorCode ? (
                <Callout tone="danger" role="alert" data-testid="meta-error">
                  {t.errorHelp[connection.lastErrorCode] ?? t.errorHelp.UNEXPECTED_RESPONSE}
                </Callout>
              ) : null}
              {connection.status === "CONNECTED" && connection.lastSyncError ? (
                <Callout tone="warning" role="note">{t.syncError(connection.lastSyncError)}</Callout>
              ) : null}
              <dl className="details">
                <dt>{t.canPause}</dt>
                <dd data-testid="meta-can-write">{connection.canWrite ? t.yes : t.noReadOnly}</dd>
                <dt>{t.lastSync}</dt>
                <dd>{formatInstant(connection.lastSyncAt, zone, locale)}</dd>
                <dt>{t.adsSeen}</dt>
                <dd data-testid="meta-ads-seen">{connection.adsSeen}</dd>
                <dt>{t.lastCheck}</dt>
                <dd>{formatInstant(connection.lastCheckAt, zone, locale)}</dd>
              </dl>
              {canManage && connection.allowedActions.includes("RUN_CHECK") ? (
                <div className="form-actions inline-form">
                  <form action={checkMeta.bind(null, storeId, connection.id)}>
                    <button type="submit" className="button secondary">{t.check}</button>
                  </form>
                </div>
              ) : null}
            </section>

            <section className="card" aria-labelledby="mappings-title">
              <h2 id="mappings-title">{messages.adMappings.title}</h2>
              <p className="muted">{messages.adMappings.intro}</p>
              <Link href={`${integrations}/ads`} className="button secondary" data-testid="meta-mappings-link">
                {messages.adMappings.openFromMeta}
              </Link>
            </section>

            <section className="card" aria-labelledby="connection-title">
              <h2 id="connection-title">{ti.sectionConnection}</h2>
              <dl className="details">
                <dt>{t.accountId}</dt>
                <dd>act_{connection.accountId}</dd>
                <dt>{t.accountName}</dt>
                <dd>{connection.accountName ?? "—"}</dd>
                <dt>{t.currency}</dt>
                <dd>{connection.currency ?? "—"}</dd>
                <dt>{t.savedToken}</dt>
                <dd>{connection.tokenHint ? `…${connection.tokenHint}` : t.tokenNotSaved}</dd>
              </dl>
              {canManage ? (
                <>
                  <h3 className="subheading">{t.changeToken}</h3>
                  <MetaForm storeId={storeId} connectionId={connection.id} />
                </>
              ) : (
                <p className="muted">{t.readOnly}</p>
              )}
            </section>

            <details className="card technical-card">
              <summary>{ti.sectionTechnical}</summary>
              <dl className="details">
                <dt>{ti.connectionId}</dt>
                <dd><code>{connection.id}</code></dd>
              </dl>
            </details>
          </>
        ) : canManage ? (
          <section className="card connect-flow" aria-labelledby="connect-title">
            <div className="connect-guide">
              <h2 id="connect-title">{ti.guideTitle}</h2>
              <p className="muted">{t.noConnection}</p>
              <ol className="guide-steps">
                {t.guide.map((step) => (
                  <li key={step}>{step}</li>
                ))}
              </ol>
            </div>
            <div className="connect-form">
              <h2 className="subheading">{ti.connect} Meta Ads</h2>
              <MetaForm storeId={storeId} />
            </div>
          </section>
        ) : (
          <StatePanel tone="info" title={ti.readOnlyTitle} description={t.noConnection} />
        )}
      </div>
    </>
  );
}
