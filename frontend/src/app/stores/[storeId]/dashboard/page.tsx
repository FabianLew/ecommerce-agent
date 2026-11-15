import Link from "next/link";
import { notFound } from "next/navigation";

import type { components } from "@/api/schema";
import { DailyBars } from "@/components/DailyBars";
import { MetricCard } from "@/components/MetricCard";
import { PageHeading } from "@/components/PageHeading";
import { formatInstant } from "@/i18n/format";
import { INTL_TAGS } from "@/i18n/locale";
import type { Messages } from "@/i18n/messages";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath, storePath } from "@/lib/store-path";

import { describe } from "../activity/describe";

export const dynamic = "force-dynamic";

type Attention = components["schemas"]["AttentionItem"];

/** Dashboard v1 (04 §4): the last 7 full days, what needs attention, recent activity and integration health. */
export default async function DashboardPage({ params }: { params: Promise<{ storeId: string }> }) {
  const [{ storeId }, locale, messages] = await Promise.all([params, getLocale(), getMessages()]);
  const t = messages.dashboard;
  const path = storePath(storeId);
  const api = await backend(path);
  const [{ data: store, response }, { data }] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/dashboard", { params: { path: { storeId } } }),
  ]);
  redirectIfUnauthenticated(response.status, path);
  if (!store || !data) {
    notFound();
  }
  const zone = data.meta.timezone;
  const money = new Intl.NumberFormat(INTL_TAGS[locale], { style: "currency", currency: data.meta.currency });

  return (
    <>
      <PageHeading>{messages.sections.dashboard}</PageHeading>
      <p className="muted">{t.lastSevenDays(data.meta.from, data.meta.to, zone)}</p>
      <div className="metric-grid">
        {data.metrics.map((metric) => (
          <MetricCard key={metric.key} metric={metric} locale={locale} t={messages.analytics} />
        ))}
      </div>

      <section className="card" data-testid="dashboard-attention" aria-labelledby="dashboard-attention">
        <h2 id="dashboard-attention">{t.attention}</h2>
        {data.decisionsToApprove > 0 ? (
          <p>
            {t.decisionsToApprove(data.decisionsToApprove)}{" "}
            <Link href={storePath(storeId, "decisions")}>{t.openDecisions}</Link>
          </p>
        ) : null}
        {data.attentionItems.length === 0 ? (
          <p className="muted">{t.nothingNeedsAttention}</p>
        ) : (
          <ul className="stack">
            {data.attentionItems.map((item, index) => (
              <li key={`${item.kind}-${item.resourceId ?? index}`}>
                <span className="badge">{t.attentionKinds[item.kind]}</span>{" "}
                <Link href={attentionLink(storeId, item)}>{attentionText(item, messages)}</Link>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card" data-testid="dashboard-trend">
        <DailyBars
          title={t.trendTitle}
          noDataLabel={messages.analytics.trendNoData}
          locale={locale}
          points={data.salesTrend.map((point) => ({
            day: point.day,
            value: point.netSales == null ? null : Number(point.netSales),
            label:
              point.netSales == null
                ? messages.analytics.trendNoData
                : `${money.format(Number(point.netSales))} · ${point.count}`,
          }))}
        />
        <Link href={storePath(storeId, "analytics")}>{messages.sections.analytics}</Link>
      </section>

      <section className="card" data-testid="dashboard-health" aria-labelledby="dashboard-health">
        <h2 id="dashboard-health">{t.health}</h2>
        {data.integrationHealth.length === 0 ? (
          <p className="muted">
            {t.noIntegrations} <Link href={storePath(storeId, "integrations")}>{t.open}</Link>
          </p>
        ) : (
          <ul className="stack">
            {data.integrationHealth.map((item) => (
              <li key={item.connectionId}>
                <strong>{t.sources[item.source]}</strong>: {healthStatus(item.source, item.status, messages)}
                {item.dataUntil ? <span className="muted"> · {t.dataUntil(formatInstant(item.dataUntil, zone, locale))}</span> : null}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card" data-testid="dashboard-activity" aria-labelledby="dashboard-activity">
        <h2 id="dashboard-activity">{t.recentActivity}</h2>
        {data.recentActivities.length === 0 ? (
          <p className="muted">{t.noActivity}</p>
        ) : (
          <ul className="stack">
            {data.recentActivities.map((item) => (
              <li key={item.id}>
                <span className="muted nowrap">{formatInstant(item.occurredAt, zone, locale)}</span>{" "}
                {describe(item, messages)}
              </li>
            ))}
          </ul>
        )}
        <Link href={storePath(storeId, "activity")}>{t.allActivity}</Link>
      </section>

      <section className="card" aria-labelledby="store-summary">
        <h2 id="store-summary">{messages.store.summary}</h2>
        <dl className="details">
          <dt>{messages.store.name}</dt>
          <dd data-testid="store-name">{store.name}</dd>
          <dt>{messages.store.organization}</dt>
          <dd>{store.organizationName}</dd>
          <dt>{messages.store.currency}</dt>
          <dd>{store.currency}</dd>
          <dt>{messages.store.timezone}</dt>
          <dd>{store.timezone}</dd>
          <dt>{messages.store.roles}</dt>
          <dd>{store.roles.map((role) => messages.store.roleLabels[role] ?? role).join(", ")}</dd>
          <dt>{messages.store.permissions}</dt>
          <dd>
            {store.permissions
              .map((permission) => messages.store.permissionLabels[permission] ?? permission)
              .join(", ")}
          </dd>
        </dl>
      </section>
    </>
  );
}

function attentionLink(storeId: string, item: Attention): string {
  switch (item.kind) {
    case "DECISION_TO_APPROVE":
      return item.resourceId ? decisionPath(storeId, item.resourceId) : storePath(storeId, "decisions");
    case "INTEGRATION_UNHEALTHY":
    case "META_ERROR":
      return storePath(storeId, "integrations");
    case "ONBOARDING_INCOMPLETE":
      return `/stores/${encodeURIComponent(storeId)}/onboarding`;
  }
}

function attentionText(item: Attention, messages: Messages): string {
  const t = messages.dashboard;
  switch (item.kind) {
    case "DECISION_TO_APPROVE":
      return item.summary ?? t.attentionKinds.DECISION_TO_APPROVE;
    case "INTEGRATION_UNHEALTHY":
      return t.integrationIssue(healthStatus("WOOCOMMERCE", item.code ?? "", messages));
    case "META_ERROR":
      return t.metaIssue(item.code ?? "");
    case "ONBOARDING_INCOMPLETE":
      return t.onboardingNext(
        messages.onboarding.steps[item.code as keyof typeof messages.onboarding.steps] ?? item.code ?? "",
      );
  }
}

function healthStatus(source: "WOOCOMMERCE" | "META", status: string, messages: Messages): string {
  const labels: Record<string, string> =
    source === "WOOCOMMERCE" ? messages.integrations.healthStatus : messages.meta.status;
  return labels[status] ?? status;
}
