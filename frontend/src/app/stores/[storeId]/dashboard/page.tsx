import { ChevronRight, CircleCheck } from "lucide-react";
import Link from "next/link";
import { notFound } from "next/navigation";

import type { components } from "@/api/schema";
import { DailyBars } from "@/components/DailyBars";
import { MetricCard } from "@/components/MetricCard";
import { PageHeading } from "@/components/PageHeading";
import { Callout } from "@/components/ui/Callout";
import { Icon } from "@/components/ui/Icon";
import { StatusBadge } from "@/components/ui/StatusBadge";
import type { Tone } from "@/components/ui/tones";
import { CONNECTION_STATUS_TONE, HEALTH_STATUS_TONE, PROPOSAL_STATUS_TONE, toneOf } from "@/components/ui/tones";
import { formatCount, formatInstant, formatRelative, plural } from "@/i18n/format";
import type { Locale } from "@/i18n/locale";
import { INTL_TAGS } from "@/i18n/locale";
import type { Messages } from "@/i18n/messages";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath, storePath } from "@/lib/store-path";

import { describe } from "../activity/describe";
import { label, problemTitle, recommendationText } from "../decisions/labels";

export const dynamic = "force-dynamic";

type Attention = components["schemas"]["AttentionItem"];
type Health = components["schemas"]["HealthItem"];
type Decision = components["schemas"]["DecisionSummary"];

/**
 * Overview (plan 4.A, 04 §4): an operations cockpit. Monitoring state and what needs the user come first, then the
 * business pulse for the last 7 full days. Every number comes from the API; a missing value is shown as missing.
 */
export default async function DashboardPage({ params }: { params: Promise<{ storeId: string }> }) {
  const [{ storeId }, locale, messages] = await Promise.all([params, getLocale(), getMessages()]);
  const t = messages.dashboard;
  const path = storePath(storeId);
  const api = await backend(path);
  const store = { params: { path: { storeId } } };
  const [storeRead, dashboard, decisions, onboarding, stop] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}", store),
    api.GET("/api/v1/stores/{storeId}/dashboard", store),
    api.GET("/api/v1/stores/{storeId}/decisions", {
      params: { path: { storeId }, query: { group: "TO_APPROVE", limit: 5 } },
    }),
    api.GET("/api/v1/stores/{storeId}/onboarding", store),
    api.GET("/api/v1/stores/{storeId}/mutation-stop", store),
  ]);
  redirectIfUnauthenticated(storeRead.response.status, path);
  const data = dashboard.data;
  if (!storeRead.data || !data) {
    notFound();
  }
  const zone = data.meta.timezone;
  const now = new Date();
  const money = new Intl.NumberFormat(INTL_TAGS[locale], { style: "currency", currency: data.meta.currency });
  const day = new Intl.DateTimeFormat(INTL_TAGS[locale], { dateStyle: "medium", timeZone: "UTC" });
  const dayOf = (value: string) => day.format(new Date(`${value}T00:00:00Z`));

  const monitoring = onboarding.data?.monitoring;
  const steps = onboarding.data?.steps ?? [];
  const setupOpen = onboarding.data !== undefined && !monitoring?.startedAt && steps.length > 0;
  const stopped = Boolean(stop.data?.stopped || stop.data?.globallyStopped);
  const healthy = data.integrationHealth.filter(isHealthy).length;
  const toApprove = decisions.data?.items ?? [];
  const waiting = decisions.data?.counts.toApprove ?? data.decisionsToApprove;
  // Decisions are listed as rows of their own, so the generic "decision to approve" items are not repeated.
  const otherAttention = data.attentionItems.filter(
    (item) => item.kind !== "DECISION_TO_APPROVE" || decisions.data === undefined,
  );
  const nothing = waiting === 0 && toApprove.length === 0 && otherAttention.length === 0;

  return (
    <>
      <header className="page-header">
        <PageHeading>{messages.sections.dashboard}</PageHeading>
        <p className="page-sub">
          <span data-testid="store-name">{storeRead.data.name}</span>
          <span aria-hidden="true"> · </span>
          {t.period(dayOf(data.meta.from), dayOf(data.meta.to))}
        </p>
      </header>

      {setupOpen ? (
        <Callout tone="info" title={t.setupTitle} data-testid="overview-setup" role="note">
          <p>{t.setupText(steps.filter((step) => step.complete).length, steps.length)}</p>
          <Link href={`/stores/${encodeURIComponent(storeId)}/onboarding`} className="button primary">
            {t.setupAction}
          </Link>
        </Callout>
      ) : null}

      <section className="status-band" aria-labelledby="overview-status" data-testid="overview-status">
        <h2 id="overview-status" className="visually-hidden">{t.statusTitle}</h2>
        <dl className="status-items">
          {monitoring ? (
            <StatusItem
              label={t.statusMonitoring}
              tone={monitoring.startedAt ? "success" : "neutral"}
              value={monitoring.startedAt ? t.monitoringSince(formatInstant(monitoring.startedAt, zone, locale)) : t.monitoringOff}
            />
          ) : null}
          {monitoring ? (
            <StatusItem
              label={t.statusAdChanges}
              tone={monitoring.ads === "OFF" ? "neutral" : "info"}
              value={t.adLevels[monitoring.ads] ?? monitoring.ads}
            />
          ) : null}
          {stop.data ? (
            <StatusItem
              label={t.statusChanges}
              tone={stopped ? "danger" : "success"}
              value={stopped ? t.changesStopped : t.changesNotStopped}
              href={storePath(storeId, "settings")}
            />
          ) : null}
          <StatusItem
            label={t.statusSources}
            tone={data.integrationHealth.length === 0 ? "neutral" : healthy === data.integrationHealth.length ? "success" : "warning"}
            value={data.integrationHealth.length === 0 ? t.noSources : t.sourcesSummary(healthy, data.integrationHealth.length)}
            href={storePath(storeId, "integrations")}
          />
        </dl>
      </section>

      <div className="overview-layout">
        <div className="overview-main">
          <section className="card" data-testid="dashboard-attention" aria-labelledby="dashboard-attention">
            <div className="card-header">
              <h2 id="dashboard-attention">{t.attention}</h2>
              {waiting > 0 ? (
                <Link href={storePath(storeId, "decisions")} className="card-link">
                  {t.openDecisions}
                </Link>
              ) : null}
            </div>
            {nothing ? (
              <div className="quiet-state">
                <Icon icon={CircleCheck} />
                <div>
                  <strong>{t.nothingTitle}</strong>
                  <p className="muted">{t.updatedAt(formatRelative(data.meta.computedAt, locale, now))}</p>
                </div>
              </div>
            ) : (
              <>
                {waiting > 0 ? (
                  <p className="attention-count">
                    <strong>{formatCount(waiting, locale)}</strong> {plural(locale, waiting, t.waitingForms)}
                  </p>
                ) : null}
                <ul className="attention-list">
                  {toApprove.map((item) => (
                    <DecisionRow key={item.proposalId} storeId={storeId} item={item} messages={messages} locale={locale}
                      now={now} />
                  ))}
                  {otherAttention.map((item, index) => (
                    <li key={`${item.kind}-${item.resourceId ?? index}`} className="attention-row">
                      <div className="attention-main">
                        <span className="attention-kind">{t.attentionKinds[item.kind]}</span>
                        <Link href={attentionLink(storeId, item)} className="attention-link">
                          {attentionText(item, messages)}
                        </Link>
                      </div>
                      <Icon icon={ChevronRight} size="sm" />
                    </li>
                  ))}
                </ul>
              </>
            )}
          </section>

          <section className="card" data-testid="dashboard-pulse" aria-labelledby="dashboard-pulse">
            <div className="card-header">
              <h2 id="dashboard-pulse">{t.pulse}</h2>
              <Link href={storePath(storeId, "analytics")} className="card-link">{messages.sections.analytics}</Link>
            </div>
            <div className="metric-grid pulse-metrics">
              {data.metrics.map((metric) => (
                <MetricCard key={metric.key} metric={metric} locale={locale} t={messages.analytics}
                  unlock={metric.missingComponents.some((reason) => SOURCE_REASONS.has(reason))
                    ? { href: storePath(storeId, "integrations"), label: t.unlock }
                    : undefined} />
              ))}
            </div>
            <div className="pulse-chart" data-testid="dashboard-trend">
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
              <details className="disclosure">
                <summary>{t.showTable}</summary>
                <table className="data-table">
                  <thead>
                    <tr>
                      <th scope="col">{t.day}</th>
                      <th scope="col" className="numeric">{t.netSales}</th>
                      <th scope="col" className="numeric">{t.orders}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.salesTrend.map((point) => (
                      <tr key={point.day}>
                        <td>{dayOf(point.day)}</td>
                        <td className="numeric">
                          {point.netSales == null ? messages.analytics.trendNoData : money.format(Number(point.netSales))}
                        </td>
                        <td className="numeric">{point.netSales == null ? "—" : formatCount(point.count, locale)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </details>
            </div>
          </section>
        </div>

        <aside className="overview-side">
          <section className="card" data-testid="dashboard-health" aria-labelledby="dashboard-health">
            <div className="card-header">
              <h2 id="dashboard-health">{t.health}</h2>
              <Link href={storePath(storeId, "integrations")} className="card-link">{t.open}</Link>
            </div>
            {data.integrationHealth.length === 0 ? (
              <p className="muted">{t.noIntegrations}</p>
            ) : (
              <ul className="source-list">
                {data.integrationHealth.map((item) => (
                  <li key={item.connectionId} className="source-row">
                    <strong>{t.sources[item.source]}</strong>
                    <StatusBadge tone={healthTone(item)}>{healthStatus(item.source, item.status, messages)}</StatusBadge>
                    {item.dataUntil ? (
                      <span className="source-sub">{t.dataUntil(formatInstant(item.dataUntil, zone, locale))}</span>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="card" data-testid="dashboard-activity" aria-labelledby="dashboard-activity">
            <div className="card-header">
              <h2 id="dashboard-activity">{t.recentActivity}</h2>
              <Link href={storePath(storeId, "activity")} className="card-link">{t.allActivity}</Link>
            </div>
            {data.recentActivities.length === 0 ? (
              <p className="muted">{t.noActivity}</p>
            ) : (
              <ol className="activity-feed">
                {data.recentActivities.slice(0, 5).map((item) => (
                  <li key={item.id}>
                    <span>{describe(item, messages)}</span>
                    <time dateTime={item.occurredAt} className="activity-time" title={formatInstant(item.occurredAt, zone, locale)}>
                      {formatRelative(item.occurredAt, locale, now)}
                    </time>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </aside>
      </div>
    </>
  );
}

/** Reasons that a connected data source fixes; other reasons (e.g. unknown costs) get no link. */
const SOURCE_REASONS = new Set(["META_INSIGHTS_NOT_IMPORTED", "SALES_HISTORY_INCOMPLETE"]);

function StatusItem({ label, value, tone, href }: { label: string; value: string; tone: Tone; href?: string }) {
  return (
    <div className="status-item" data-tone={tone}>
      <dt>{label}</dt>
      <dd>
        <span className="status-dot" aria-hidden="true" />
        {href ? <Link href={href}>{value}</Link> : value}
      </dd>
    </div>
  );
}

function DecisionRow({
  storeId,
  item,
  messages,
  locale,
  now,
}: {
  storeId: string;
  item: Decision;
  messages: Messages;
  locale: Locale;
  now: Date;
}) {
  const t = messages.decisions;
  const expired = new Date(item.expiresAt).getTime() < now.getTime();
  return (
    <li className="attention-row decision-row">
      <div className="attention-main">
        <Link href={decisionPath(storeId, item.proposalId)} className="attention-link">
          {problemTitle(item, t)}
        </Link>
        <span className="attention-sub">{recommendationText(item, t)}</span>
      </div>
      <div className="attention-meta">
        <StatusBadge tone={toneOf(PROPOSAL_STATUS_TONE, item.status)}>{label(t.proposalStatus, item.status)}</StatusBadge>
        <span className={expired ? "inbox-deadline expired" : "inbox-deadline"}>
          {expired
            ? t.expiredAgo(formatRelative(item.expiresAt, locale, now))
            : t.detail.decideIn(formatRelative(item.expiresAt, locale, now))}
        </span>
      </div>
      <Link href={decisionPath(storeId, item.proposalId)} className="button secondary sm" aria-hidden="true" tabIndex={-1}>
        {messages.dashboard.review}
      </Link>
    </li>
  );
}

function isHealthy(item: Health): boolean {
  return item.source === "WOOCOMMERCE" ? item.status === "HEALTHY" : item.status === "CONNECTED";
}

function healthTone(item: Health): Tone {
  return item.source === "WOOCOMMERCE"
    ? toneOf(HEALTH_STATUS_TONE, item.status)
    : toneOf(CONNECTION_STATUS_TONE, item.status);
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
