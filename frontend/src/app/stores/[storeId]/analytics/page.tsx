import Link from "next/link";
import { notFound } from "next/navigation";

import { DailyBars } from "@/components/DailyBars";
import { MetricCard, metricText } from "@/components/MetricCard";
import { PageHeading } from "@/components/PageHeading";
import { formatCount, formatInstant } from "@/i18n/format";
import { Callout } from "@/components/ui/Callout";
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
      <div className="toolbar">
      <nav className="tabs" aria-label={messages.sections.analytics}>
        {(["sales", "products"] as const).map((item) => (
          <Link key={item} href={link({ tab: item, sort: undefined, offset: undefined })} className="tab"
            aria-current={item === tab ? "page" : undefined} data-testid={`analytics-tab-${item}`}>
            {t.tabs[item]}
          </Link>
        ))}
      </nav>
      <form method="get" action={path} className="period-form" data-testid="analytics-period"
        aria-label={t.periodTitle}>
        <input type="hidden" name="tab" value={tab} />
        <div className="field">
          <label htmlFor="period-from">{t.from}</label>
          <input id="period-from" type="date" name="from" defaultValue={from} />
        </div>
        <div className="field">
          <label htmlFor="period-to">{t.to}</label>
          <input id="period-to" type="date" name="to" defaultValue={to} />
        </div>
        <button type="submit" className="button secondary">{t.apply}</button>
      </form>
      </div>
      {tab === "sales" ? (
        <Sales storeId={storeId} from={from} to={to} locale={locale} t={t} path={path} />
      ) : (
        <Products storeId={storeId} from={from} to={to} locale={locale} t={t} query={query} link={link} path={path} />
      )}
    </>
  );
}

type Meta = { from: string; to: string; timezone: string; coveredFrom?: string; coveredUntil?: string; fullyCovered: boolean };

function formatDay(day: string, locale: Locale): string {
  return new Intl.DateTimeFormat(INTL_TAGS[locale], { dateStyle: "medium", timeZone: "UTC" })
    .format(new Date(`${day}T00:00:00Z`));
}

function Coverage({ meta, locale, t }: { meta: Meta; locale: Locale; t: Messages["analytics"] }) {
  return (
    <>
      <p className="muted period-line">{t.period(formatDay(meta.from, locale), formatDay(meta.to, locale), meta.timezone)}</p>
      {!meta.coveredFrom ? (
        <Callout tone="warning" role="note" data-testid="analytics-uncovered">{t.uncovered}</Callout>
      ) : !meta.fullyCovered ? (
        <Callout tone="warning" role="note" data-testid="analytics-partial">
          {t.partial(formatInstant(meta.coveredFrom, meta.timezone, locale),
            formatInstant(meta.coveredUntil, meta.timezone, locale))}
        </Callout>
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
      return <Callout tone="warning" role="note">{t.uncovered}</Callout>;
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
        <details className="disclosure">
          <summary>{t.showTable}</summary>
          <table className="data-table">
            <thead>
              <tr><th scope="col">{t.day}</th><th scope="col" className="numeric">{t.netSales}</th>
                <th scope="col" className="numeric">{t.orders}</th></tr>
            </thead>
            <tbody>
              {data.series.map((point) => (
                <tr key={point.day}>
                  <td>{formatDay(point.day, locale)}</td>
                  <td className="numeric">{point.netSales == null ? t.quality.UNAVAILABLE : money.format(Number(point.netSales))}</td>
                  <td className="numeric">{point.netSales == null ? "—" : formatCount(point.count, locale)}</td>
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
      <div className="sort-row">
        <span className="muted">{t.sortBy}</span>
        <nav className="tabs" aria-label={t.sortLabel}>
          {SORTS.map((item) => (
            <Link key={item} href={link({ sort: item })} aria-current={item === sort ? "true" : undefined}
              className="tab">{t.sorts[item]}</Link>
          ))}
        </nav>
      </div>
      {data.items.length === 0 ? <p className="muted">{t.noProducts}</p> : (
        <div className="card table-card">
        <table className="data-table" data-testid="products-table">
          <thead>
            <tr><th scope="col">{t.product}</th><th scope="col" className="numeric">{t.units}</th>
              <th scope="col" className="numeric">{t.netSales}</th><th scope="col" className="numeric">{t.stock}</th>
              <th scope="col" className="numeric">{t.metricNames.days_of_cover}</th></tr>
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
                <td className="numeric">{row.units == null ? t.quality.UNAVAILABLE : formatCount(row.units, locale)}</td>
                <td className="numeric">{row.netSales == null ? t.quality.UNAVAILABLE : money.format(Number(row.netSales))}</td>
                <td className="numeric">{row.availableToSell == null ? "—" : formatCount(row.availableToSell, locale)}</td>
                <td className="numeric" title={row.daysOfCover.missingComponents.map((r) => t.reasons[r] ?? r).join("; ")}>
                  {metricText(row.daysOfCover, locale, t)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
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
