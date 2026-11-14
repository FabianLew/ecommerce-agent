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
