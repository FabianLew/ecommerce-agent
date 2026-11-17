import type { Locale } from "@/i18n/locale";
import { INTL_TAGS } from "@/i18n/locale";

export type DailyPoint = { day: string; value: number | null; label: string };

/**
 * One series of daily bars as inline SVG. A day without data has no bar and a dotted baseline mark, so a gap is never
 * drawn as zero. Each bar has a native tooltip; the caller also offers a table view.
 */
export function DailyBars({
  points,
  title,
  noDataLabel,
  locale,
}: {
  points: DailyPoint[];
  title: string;
  noDataLabel: string;
  locale: Locale;
}) {
  const width = 720;
  const height = 180;
  const padTop = 16;
  const padBottom = 24;
  const max = Math.max(0, ...points.map((point) => point.value ?? 0));
  const slot = width / Math.max(points.length, 1);
  const barWidth = Math.max(2, Math.min(24, slot * 0.6));
  const plot = height - padTop - padBottom;
  const dateFormat = new Intl.DateTimeFormat(INTL_TAGS[locale], { day: "numeric", month: "short", timeZone: "UTC" });
  const labelEvery = Math.ceil(points.length / 6);
  return (
    <figure className="chart">
      <figcaption className="chart-title">{title}</figcaption>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={title} className="chart-svg">
        <line x1={0} x2={width} y1={height - padBottom} y2={height - padBottom} className="chart-axis" />
        {points.map((point, index) => {
          const x = index * slot + (slot - barWidth) / 2;
          const day = dateFormat.format(new Date(`${point.day}T00:00:00Z`));
          if (point.value === null) {
            return (
              <g key={point.day}>
                <title>{`${day}: ${noDataLabel}`}</title>
                <rect x={index * slot} y={padTop} width={slot} height={plot} fill="transparent" />
                <line
                  x1={x}
                  x2={x + barWidth}
                  y1={height - padBottom}
                  y2={height - padBottom}
                  className="chart-gap"
                />
              </g>
            );
          }
          const h = max === 0 ? 0 : (point.value / max) * plot;
          return (
            <g key={point.day}>
              <title>{`${day}: ${point.label}`}</title>
              <rect x={index * slot} y={padTop} width={slot} height={plot} fill="transparent" />
              {h > 0 ? (
                <rect x={x} y={height - padBottom - h} width={barWidth} height={h} rx={Math.min(4, barWidth / 2)}
                  className="chart-bar" />
              ) : null}
              {index % labelEvery === 0 ? (
                <text x={index * slot + slot / 2} y={height - 6} className="chart-tick" textAnchor="middle">
                  {day}
                </text>
              ) : null}
            </g>
          );
        })}
      </svg>
    </figure>
  );
}
