import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { Callout } from "@/components/ui/Callout";
import { formatInstant } from "@/i18n/format";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

import { revokeMapping } from "./actions";
import { MappingForm } from "./MappingForm";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { MAPPING_STATE_TONE, toneOf } from "@/components/ui/tones";

export const dynamic = "force-dynamic";

/** Ad mappings (B-32): the operator confirms what each ad promotes, for its current creative. */
export default async function AdMappingsPage({ params }: { params: Promise<{ storeId: string }> }) {
  const { storeId } = await params;
  const integrations = storePath(storeId, "integrations");
  const path = `${integrations}/ads`;
  const api = await backend(path);
  const [store, ads, variants, locale, messages] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/ads", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/variants", { params: { path: { storeId } } }),
    getLocale(),
    getMessages(),
  ]);
  redirectIfUnauthenticated(ads.response.status, path);
  if (!store.data || !ads.data || !variants.data) {
    notFound();
  }
  const t = messages.adMappings;
  const timezone = store.data.timezone;
  const canManage = store.data.permissions.includes("STORE_MANAGE");

  return (
    <>
      <header className="page-header">
        <PageHeading>{t.title}</PageHeading>
        <p className="page-sub">{t.intro}</p>
      </header>
      {canManage ? null : <Callout tone="info" role="note">{t.readOnly}</Callout>}
      {ads.data.items.length === 0 ? (
        <div data-testid="ad-mapping-empty">
          <StatePanel tone="info" title={t.title} description={t.empty} />
        </div>
      ) : (
        <ul className="ad-list" data-testid="ad-mapping-list">
          {ads.data.items.map((ad) => {
            const confirmed = ad.mappingState === "VERIFIED";
            return (
              <li key={ad.id} className="card ad-row" data-testid={`ad-${ad.externalId}`}>
                <div className="card-header">
                  <h2>{ad.name}</h2>
                  <StatusBadge tone={toneOf(MAPPING_STATE_TONE, ad.mappingState)} data-testid="mapping-state">
                    {t.state[ad.mappingState]}
                  </StatusBadge>
                </div>
                <p className="ad-meta">
                  {ad.externalId} · {ad.accountExternalId} · {ad.effectiveStatus} · {t.observed}{" "}
                  {formatInstant(ad.observedAt, timezone, locale)}
                </p>
                <p className="ad-mapping" data-testid="mapping-summary">
                  <span className="ad-mapping-label">{t.mapping}</span>
                  {ad.mapping ? (
                    <span>
                      {t.scope[ad.mapping.scope]}:{" "}
                      <strong>{ad.mapping.variants.map((variant) => variant.sku ?? variant.name).join(", ")}</strong>
                      <span className="muted">
                        {" "}({t.version(ad.mapping.version)}
                        {ad.mapping.verifiedAt ? `, ${t.confirmedAt(formatInstant(ad.mapping.verifiedAt, timezone, locale))}` : ""})
                      </span>
                    </span>
                  ) : (
                    <span>—</span>
                  )}
                </p>
                {ad.allowedActions.includes("CONFIRM_MAPPING") ? (
                  <details className="ad-edit" open={!confirmed}>
                    <summary>{confirmed ? t.change : t.confirm}</summary>
                    <MappingForm storeId={storeId} ad={ad} variants={variants.data.items} />
                  </details>
                ) : null}
                {ad.allowedActions.includes("REVOKE_MAPPING") && ad.mapping ? (
                  <form action={revokeMapping.bind(null, storeId, ad.id, ad.mapping.version)}>
                    <button type="submit" className="button ghost sm">{t.revoke}</button>
                  </form>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
    </>
  );
}
