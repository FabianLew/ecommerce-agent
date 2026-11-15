import Link from "next/link";
import { notFound } from "next/navigation";

import { DailyBars } from "@/components/DailyBars";
import { MetricCard } from "@/components/MetricCard";
import { PageHeading } from "@/components/PageHeading";
import { formatInstant } from "@/i18n/format";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath, storePath } from "@/lib/store-path";

export const dynamic = "force-dynamic";

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Product drill-down (04 §7): daily units, stock readings, mapped ads and the variant's decisions. */
export default async function ProductDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ storeId: string; variantId: string }>;
  searchParams: Promise<{ from?: string; to?: string }>;
}) {
  const [{ storeId, variantId }, query, locale, messages] = await Promise.all([params, searchParams, getLocale(),
    getMessages()]);
  const t = messages.analytics;
  const analytics = storePath(storeId, "analytics");
  const path = `${analytics}/products/${encodeURIComponent(variantId)}`;
  const from = query.from && DATE.test(query.from) ? query.from : undefined;
  const to = query.to && DATE.test(query.to) ? query.to : undefined;
  const api = await backend(path);
  const { data, response } = await api.GET("/api/v1/stores/{storeId}/analytics/products/{variantId}", {
    params: { path: { storeId, variantId }, query: { from, to } },
  });
  redirectIfUnauthenticated(response.status, path);
  if (!data) {
    notFound();
  }
  const tz = data.meta.timezone;
  return (
    <>
      <PageHeading>
        {`${data.variant.sku ? `${data.variant.sku} · ` : ""}${data.variant.productName} – ${data.variant.name}`}
      </PageHeading>
      <p><Link href={`${analytics}?tab=products`}>{t.backToProducts}</Link></p>
      <p className="muted">{t.period(data.meta.from, data.meta.to, tz)}</p>
      <div className="metric-grid">
        <MetricCard metric={data.daysOfCover} locale={locale} t={t} />
        <MetricCard metric={data.salesVelocity} locale={locale} t={t} />
        <section className="card metric-card">
          <h2 className="metric-label">{t.stock}</h2>
          <p className="metric-value">{data.availableToSell ?? "—"}</p>
        </section>
      </div>
      <section className="card">
        <DailyBars title={t.units} noDataLabel={t.trendNoData} locale={locale}
          points={data.daily.map((point) => ({ day: point.day, value: point.count ?? null,
            label: point.count == null ? t.trendNoData : String(point.count) }))} />
      </section>
      <section className="card" data-testid="product-stock">
        <h2>{t.stockReadings}</h2>
        {data.stock.length === 0 ? <p className="muted">{t.noStockReadings}</p> : (
          <table className="data-table">
            <tbody>
              {data.stock.map((point) => (
                <tr key={point.observedAt}>
                  <td>{formatInstant(point.observedAt, tz, locale)}</td>
                  <td>{point.availableToSell ?? "—"}</td>
                  <td>{point.stockStatus}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      <section className="card" data-testid="product-ads">
        <h2>{t.linkedAds}</h2>
        {data.ads.length === 0 ? <p className="muted">{t.noLinkedAds}</p> : (
          <ul>
            {data.ads.map((ad) => (
              <li key={ad.adId}>
                {ad.name} ({ad.externalId}, {ad.ownStatus}) · {messages.adMappings.scope[
                  ad.mappingScope as "SINGLE_VARIANT" | "MULTI_PRODUCT"] ?? ad.mappingScope} ·{" "}
                {ad.verifiedForCurrentCreative ? t.verifiedAd : t.unverifiedAd}
              </li>
            ))}
          </ul>
        )}
      </section>
      <section className="card" data-testid="product-decisions">
        <h2>{t.decisionsTitle}</h2>
        {data.decisions.length === 0 ? <p className="muted">{t.noDecisions}</p> : (
          <ul>
            {data.decisions.map((decision) => (
              <li key={decision.opportunityId}>
                {formatInstant(decision.openedAt, tz, locale)} · {decision.status}
                {decision.latestProposalId ? (
                  <> · <Link href={decisionPath(storeId, decision.latestProposalId)}>{t.openDecision}</Link></>
                ) : null}
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
