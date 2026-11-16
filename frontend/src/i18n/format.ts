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

/** Word forms of a unit for `Intl.PluralRules` (plan section 5.3): Polish needs one/few/many and `other` for fractions. */
export type PluralForms = Partial<Record<Intl.LDMLPluralRule, string>> & { other: string };

/** The word form for a count in the user's language. */
export function plural(locale: Locale, count: number, forms: PluralForms, fractionDigits = 0): string {
  const rule = new Intl.PluralRules(INTL_TAGS[locale], { minimumFractionDigits: fractionDigits }).select(count);
  return forms[rule] ?? forms.other;
}

/**
 * A duration in days with one decimal at most (plan section 5.2): "2.6 days", "1 day", "2,6 dnia", "5 dni". The value
 * is rounded first so the word form matches the number that is shown.
 */
export function formatDays(value: number | string | undefined | null, locale: Locale, forms: PluralForms): string {
  const days = toNumber(value);
  if (days === undefined) {
    return "—";
  }
  const rounded = Math.round(days * 10) / 10;
  const fraction = Number.isInteger(rounded) ? 0 : 1;
  return `${formatNumber(rounded, locale, 1)} ${plural(locale, rounded, forms, fraction)}`;
}

/** A count as a whole number with grouping. */
export function formatCount(value: number | string | undefined | null, locale: Locale): string {
  const count = toNumber(value);
  return count === undefined ? "—" : formatNumber(Math.round(count), locale, 0);
}

/** A number from the API, which may send decimals as strings; anything else is unknown. */
export function toNumber(value: number | string | undefined | null): number | undefined {
  if (value === undefined || value === null || value === "") {
    return undefined;
  }
  const number = typeof value === "number" ? value : Number(value);
  return Number.isFinite(number) ? number : undefined;
}
