import type { components } from "@/api/schema";
import type { Locale } from "@/i18n/locale";
import type { Messages } from "@/i18n/messages";

import { formatInstant } from "./labels";
import { checkMeta } from "./meta-actions";
import { MetaForm } from "./MetaForm";

type Envelope = components["schemas"]["MetaConnectionEnvelope"];

/** The Meta Ads card (ADR-0017): account, token hint, write permission and the last ad read. */
export function MetaAdsCard({
  storeId,
  envelope,
  timezone,
  locale,
  t,
}: {
  storeId: string;
  envelope: Envelope;
  timezone: string;
  locale: Locale;
  t: Messages["meta"];
}) {
  const connection = envelope.connection;
  const canManage = envelope.canCreate;
  return (
    <section className="card" aria-labelledby="meta-title" data-testid="meta-card">
      <div className="card-header">
        <h2 id="meta-title">{t.title}</h2>
        {connection ? (
          <span className={`badge badge-${connection.status.toLowerCase()}`} data-testid="meta-status">
            {t.status[connection.status]}
          </span>
        ) : null}
      </div>
      {connection ? (
        <>
          {connection.status === "ERROR" && connection.lastErrorCode ? (
            <p className="form-message error" role="alert" data-testid="meta-error">
              {t.errorHelp[connection.lastErrorCode] ?? t.errorHelp.UNEXPECTED_RESPONSE}
            </p>
          ) : null}
          {connection.status === "CONNECTED" && connection.lastSyncError ? (
            <p className="form-message warning">{t.syncError(connection.lastSyncError)}</p>
          ) : null}
          <dl className="details">
            <dt>{t.accountId}</dt>
            <dd>act_{connection.accountId}</dd>
            <dt>{t.accountName}</dt>
            <dd>{connection.accountName ?? "—"}</dd>
            <dt>{t.currency}</dt>
            <dd>{connection.currency ?? "—"}</dd>
            <dt>{t.savedToken}</dt>
            <dd>{connection.tokenHint ? `…${connection.tokenHint}` : t.tokenNotSaved}</dd>
            <dt>{t.canPause}</dt>
            <dd data-testid="meta-can-write">{connection.canWrite ? t.yes : t.noReadOnly}</dd>
            <dt>{t.lastCheck}</dt>
            <dd>{formatInstant(connection.lastCheckAt, timezone, locale)}</dd>
            <dt>{t.lastSync}</dt>
            <dd>{formatInstant(connection.lastSyncAt, timezone, locale)}</dd>
            <dt>{t.adsSeen}</dt>
            <dd data-testid="meta-ads-seen">{connection.adsSeen}</dd>
          </dl>
        </>
      ) : (
        <p className="muted">{t.noConnection}</p>
      )}
      {canManage ? (
        <>
          {connection?.allowedActions.includes("RUN_CHECK") ? (
            <div className="form-actions inline-form">
              <form action={checkMeta.bind(null, storeId, connection.id)}>
                <button type="submit" className="button secondary">{t.check}</button>
              </form>
            </div>
          ) : null}
          {connection ? <h3 className="subheading">{t.changeToken}</h3> : null}
          <MetaForm storeId={storeId} connectionId={connection?.id} />
        </>
      ) : (
        <p className="muted">{t.readOnly}</p>
      )}
    </section>
  );
}
