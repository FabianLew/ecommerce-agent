import Link from "next/link";

import type { components } from "@/api/schema";
import type { Locale } from "@/i18n/locale";
import { INTL_TAGS } from "@/i18n/locale";
import type { Messages } from "@/i18n/messages";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { QUALITY_TONE, toneOf } from "@/components/ui/tones";

export type Metric = components["schemas"]["MetricDto"];

/** Formats a MetricDTO value: currency amounts with the currency, counts as integers, null as "No data". */
export function metricText(metric: Metric, locale: Locale, t: Messages["analytics"]): string {
  if (metric.value === undefined || metric.value === null) {
    return t.quality.UNAVAILABLE;
  }
  const value = Number(metric.value);
  if (metric.unit === "currency" && metric.currency) {
    return new Intl.NumberFormat(INTL_TAGS[locale], { style: "currency", currency: metric.currency }).format(value);
  }
  return new Intl.NumberFormat(INTL_TAGS[locale], { maximumFractionDigits: 2 }).format(value);
}

/** A KPI card (04 §3): value, quality badge, the reasons that limit it and the comparison. Never a fake zero. */
export function MetricCard({
  metric,
  locale,
  t,
  unlock,
}: {
  metric: Metric;
  locale: Locale;
  t: Messages["analytics"];
  /** Where to go when the value is unavailable, e.g. to connect the source it needs. */
  unlock?: { href: string; label: string };
}) {
  const comparison = metric.comparison;
  const unavailable = metric.value === undefined || metric.value === null;
  return (
    <section className="card metric-card" data-unavailable={unavailable || undefined} data-testid={`metric-${metric.key}`}>
      <h2 className="metric-label">{t.metricNames[metric.key] ?? metric.key}</h2>
      <p className="metric-value" data-testid="metric-value">{metricText(metric, locale, t)}</p>
      <p className="metric-quality">
        <StatusBadge tone={toneOf(QUALITY_TONE, metric.quality)}>{t.quality[metric.quality]}</StatusBadge>
      </p>
      {metric.missingComponents.length ? (
        <p className="muted metric-reasons">
          {metric.missingComponents.map((reason) => t.reasons[reason] ?? reason).join("; ")}
        </p>
      ) : null}
      {comparison ? (
        <p className="muted" data-testid="metric-comparison">
          {comparison.percentDifference ? t.versus(comparison.percentDifference) : t.versusNoPercent}
        </p>
      ) : null}
      {unavailable && unlock ? (
        <Link href={unlock.href} className="metric-unlock">{unlock.label}</Link>
      ) : null}
      <details className="metric-definition">
        <summary>{t.definition}</summary>
        <code>{metric.definitionVersion}</code>
      </details>
    </section>
  );
}
