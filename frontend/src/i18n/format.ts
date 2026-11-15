import { INTL_TAGS, type Locale } from "./locale";

/** Formats an instant in the store's time zone (04 §2: times shown in store time) and the user's language. */
export function formatInstant(value: string | undefined | null, timezone: string, locale: Locale): string {
  if (!value) {
    return "—";
  }
  return new Intl.DateTimeFormat(INTL_TAGS[locale], { dateStyle: "medium", timeStyle: "short", timeZone: timezone })
    .format(new Date(value));
}

/** A decimal with at most `digits` fraction digits; `—` when the value is unknown. */
export function formatNumber(value: number | undefined | null, locale: Locale, digits = 1): string {
  if (value === undefined || value === null) {
    return "—";
  }
  return new Intl.NumberFormat(INTL_TAGS[locale], { maximumFractionDigits: digits }).format(value);
}

/**
 * A money amount in its own currency (ISO 4217), never converted: store figures in the store's currency, AI costs and
 * budgets in the technical settlement currency (USD, ADR-0027 section 3.2). The API sends exact decimal strings, which
 * are formatted without passing through a binary float; very small AI costs keep up to six fraction digits.
 */
export function formatMoney(amount: string | number | undefined | null, currency: string | undefined | null,
                            locale: Locale): string {
  if (amount === undefined || amount === null || amount === "" || !currency) {
    return "—";
  }
  const text = String(amount);
  if (!/^-?\d+(\.\d+)?$/.test(text)) {
    return "—";
  }
  const fraction = text.split(".")[1]?.replace(/0+$/, "").length ?? 0;
  const format = new Intl.NumberFormat(INTL_TAGS[locale], {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: Math.min(Math.max(fraction, 2), 6),
  });
  // Intl formats decimal strings exactly (ES2023); the cast keeps the older lib types satisfied.
  return format.format(text as unknown as number);
}
