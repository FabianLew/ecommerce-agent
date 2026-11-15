import Link from "next/link";
import { notFound } from "next/navigation";

import { DailyBars } from "@/components/DailyBars";
import { MetricCard, metricText } from "@/components/MetricCard";
import { PageHeading } from "@/components/PageHeading";
import { formatInstant } from "@/i18n/format";
import { INTL_TAGS, type Locale } from "@/i18n/locale";
import type { Messages } from "@/i18n/messages";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

export const dynamic = "force-dynamic";

const SORTS = ["NET_SALES", "UNITS", "DAYS_OF_COVER"] as const;
type Sort = (typeof SORTS)[number];
const DATE = /^\d{4}-\d{2}-\d{2}$/;

type Query = { tab?: string; from?: string; to?: string; sort?: string; offset?: string };

/** Analytics (04 §7, ADR-0019): Sales and Products tabs. Numbers come from the backend's definitions. */
export default async function AnalyticsPage({
  params,
  searchParams,
}: {
  params: Promise<{ storeId: string }>;
  searchParams: Promise<Query>;
}) {
  const [{ storeId }, query, locale, messages] = await Promise.all([params, searchParams, getLocale(), getMessages()]);
  const t = messages.analytics;
  const tab = query.tab === "products" ? "products" : "sales";
  const from = query.from && DATE.test(query.from) ? query.from : undefined;
  const to = query.to && DATE.test(query.to) ? query.to : undefined;
  const path = storePath(storeId, "analytics");
  const link = (extra: Record<string, string | undefined>) => {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries({ tab, from, to, ...extra })) {
      if (value) {
        params.set(key, value);
      }
    }
    return `${path}?${params.toString()}`;
  };

  return (
    <>
      <PageHeading>{messages.sections.analytics}</PageHeading>
      <nav className="tabs" aria-label={messages.sections.analytics}>
        {(["sales", "products"] as const).map((item) => (
          <Link key={item} href={link({ tab: item, sort: undefined, offset: undefined })} className="tab"
            aria-current={item === tab ? "page" : undefined} data-testid={`analytics-tab-${item}`}>
            {t.tabs[item]}
          </Link>
        ))}
      </nav>
      <form method="get" action={path} className="form inline-form" data-testid="analytics-period">
        <input type="hidden" name="tab" value={tab} />
        <label>
          {t.from} <input type="date" name="from" defaultValue={from} />
        </label>
        <label>
          {t.to} <input type="date" name="to" defaultValue={to} />
        </label>
        <button type="submit" className="button secondary">{t.apply}</button>
      </form>
      {tab === "sales" ? (
        <Sales storeId={storeId} from={from} to={to} locale={locale} t={t} path={path} />
      ) : (
        <Products storeId={storeId} from={from} to={to} locale={locale} t={t} query={query} link={link} path={path} />
      )}
    </>
  );
}

type Meta = { from: string; to: string; timezone: string; coveredFrom?: string; coveredUntil?: string; fullyCovered: boolean };

function Coverage({ meta, locale, t }: { meta: Meta; locale: Locale; t: Messages["analytics"] }) {
  return (
    <>
      <p className="muted">{t.period(meta.from, meta.to, meta.timezone)}</p>
      {!meta.coveredFrom ? (
        <p className="form-message warning" data-testid="analytics-uncovered">{t.uncovered}</p>
      ) : !meta.fullyCovered ? (
        <p className="form-message warning" data-testid="analytics-partial">
          {t.partial(formatInstant(meta.coveredFrom, meta.timezone, locale),
            formatInstant(meta.coveredUntil, meta.timezone, locale))}
        </p>
      ) : null}
    </>
  );
}

async function Sales({ storeId, from, to, locale, t, path }: {
  storeId: string; from?: string; to?: string; locale: Locale; t: Messages["analytics"]; path: string;
}) {
  const api = await backend(path);
  const { data, response } = await api.GET("/api/v1/stores/{storeId}/analytics/sales", {
    params: { path: { storeId }, query: { from, to } },
  });
  redirectIfUnauthenticated(response.status, path);
  if (!data) {
    if (response.status === 400) {
      return <p className="form-message error">{t.uncovered}</p>;
    }
    notFound();
  }
  const money = new Intl.NumberFormat(INTL_TAGS[locale], { style: "currency", currency: data.meta.currency });
  return (
    <>
      <Coverage meta={data.meta} locale={locale} t={t} />
      <div className="metric-grid">
        {data.metrics.map((metric) => <MetricCard key={metric.key} metric={metric} locale={locale} t={t} />)}
      </div>
      {data.otherCurrencyOrders > 0 ? <p className="muted">{t.otherCurrencyOrders(data.otherCurrencyOrders)}</p> : null}
      <section className="card" data-testid="sales-trend">
        <DailyBars
          title={t.trendTitle}
          noDataLabel={t.trendNoData}
          locale={locale}
          points={data.series.map((point) => ({
            day: point.day,
            value: point.netSales == null ? null : Number(point.netSales),
            label: point.netSales == null ? t.trendNoData : `${money.format(Number(point.netSales))} · ${point.count}`,
          }))}
        />
        <details>
          <summary>{t.showTable}</summary>
          <table className="data-table">
            <thead>
              <tr><th>{t.day}</th><th>{t.netSales}</th><th>{t.orders}</th></tr>
            </thead>
            <tbody>
              {data.series.map((point) => (
                <tr key={point.day}>
                  <td>{point.day}</td>
                  <td>{point.netSales == null ? t.quality.UNAVAILABLE : money.format(Number(point.netSales))}</td>
                  <td>{point.count ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      </section>
    </>
  );
}

async function Products({ storeId, from, to, locale, t, query, link, path }: {
  storeId: string; from?: string; to?: string; locale: Locale; t: Messages["analytics"]; query: Query;
  link: (extra: Record<string, string | undefined>) => string; path: string;
}) {
  const sort: Sort = SORTS.find((item) => item === query.sort) ?? "NET_SALES";
  const offset = Math.max(0, Number.parseInt(query.offset ?? "0", 10) || 0);
  const api = await backend(path);
  const { data, response } = await api.GET("/api/v1/stores/{storeId}/analytics/products", {
    params: { path: { storeId }, query: { from, to, sort, offset, limit: 50 } },
  });
  redirectIfUnauthenticated(response.status, path);
  if (!data) {
    notFound();
  }
  const money = new Intl.NumberFormat(INTL_TAGS[locale], { style: "currency", currency: data.meta.currency });
  return (
    <>
      <Coverage meta={data.meta} locale={locale} t={t} />
      <p>
        {t.sortBy}:{" "}
        {SORTS.map((item) => (
          <Link key={item} href={link({ sort: item })} aria-current={item === sort ? "true" : undefined}
            className="tab">{t.sorts[item]}</Link>
        ))}
      </p>
      {data.items.length === 0 ? <p className="muted">{t.noProducts}</p> : (
        <table className="data-table" data-testid="products-table">
          <thead>
            <tr><th>{t.product}</th><th>{t.units}</th><th>{t.netSales}</th><th>{t.stock}</th>
              <th>{t.metricNames.days_of_cover}</th></tr>
          </thead>
          <tbody>
            {data.items.map((row) => (
              <tr key={row.variant.id}>
                <td>
                  <Link href={`${path}/products/${encodeURIComponent(row.variant.id)}${from || to ? `?${new URLSearchParams(
                    Object.entries({ from, to }).filter(([, v]) => v) as [string, string][]).toString()}` : ""}`}>
                    {row.variant.sku ? `${row.variant.sku} · ` : ""}{row.variant.productName} – {row.variant.name}
                  </Link>
                </td>
                <td>{row.units ?? t.quality.UNAVAILABLE}</td>
                <td>{row.netSales == null ? t.quality.UNAVAILABLE : money.format(Number(row.netSales))}</td>
                <td>{row.availableToSell ?? "—"}</td>
                <td title={row.daysOfCover.missingComponents.map((r) => t.reasons[r] ?? r).join("; ")}>
                  {metricText(row.daysOfCover, locale, t)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="form-actions inline-form">
        {offset > 0 ? <Link className="button secondary" href={link({ sort, offset: String(Math.max(0, offset - 50)) })}>
          {t.previousPage}</Link> : null}
        {data.hasMore ? <Link className="button secondary" href={link({ sort, offset: String(offset + 50) })}>
          {t.nextPage}</Link> : null}
      </div>
    </>
  );
}
