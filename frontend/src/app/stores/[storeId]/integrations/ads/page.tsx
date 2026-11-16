import Link from "next/link";
import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
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
      <PageHeading>{t.title}</PageHeading>
      <p className="muted">{t.intro}</p>
      <p>
        <Link href={integrations}>{t.backToIntegrations}</Link>
      </p>
      {canManage ? null : <p className="form-message">{t.readOnly}</p>}
      {ads.data.items.length === 0 ? (
        <p className="muted" data-testid="ad-mapping-empty">{t.empty}</p>
      ) : (
        <ul className="stack" data-testid="ad-mapping-list">
          {ads.data.items.map((ad) => (
            <li key={ad.id} className="card" data-testid={`ad-${ad.externalId}`}>
              <div className="card-header">
                <h2>{ad.name}</h2>
                <StatusBadge tone={toneOf(MAPPING_STATE_TONE, ad.mappingState)} data-testid="mapping-state">
                  {t.state[ad.mappingState]}
                </StatusBadge>
              </div>
              <dl className="details">
                <dt>{t.adColumn}</dt>
                <dd>
                  {ad.externalId} · {ad.accountExternalId}
                </dd>
                <dt>{t.status}</dt>
                <dd>
                  {ad.ownStatus} / {ad.effectiveStatus}
                </dd>
                <dt>{t.observed}</dt>
                <dd>{formatInstant(ad.observedAt, timezone, locale)}</dd>
                <dt>{t.mapping}</dt>
                <dd data-testid="mapping-summary">
                  {ad.mapping ? (
                    <>
                      {t.scope[ad.mapping.scope]}:{" "}
                      {ad.mapping.variants.map((variant) => variant.sku ?? variant.name).join(", ")} (
                      {t.version(ad.mapping.version)}
                      {ad.mapping.verifiedAt ? `, ${t.confirmedAt(formatInstant(ad.mapping.verifiedAt, timezone, locale))}` : ""})
                    </>
                  ) : (
                    "—"
                  )}
                </dd>
              </dl>
              {ad.allowedActions.includes("CONFIRM_MAPPING") ? (
                <MappingForm storeId={storeId} ad={ad} variants={variants.data.items} />
              ) : null}
              {ad.allowedActions.includes("REVOKE_MAPPING") && ad.mapping ? (
                <form action={revokeMapping.bind(null, storeId, ad.id, ad.mapping.version)}>
                  <button type="submit" className="button secondary">{t.revoke}</button>
                </form>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
