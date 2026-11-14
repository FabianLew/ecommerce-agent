/** Interface languages (ADR-0013). English is the default; Polish is the second language. */
export const LOCALES = ["en", "pl"] as const;

export type Locale = (typeof LOCALES)[number];

export const DEFAULT_LOCALE: Locale = "en";

/** Cookie with the user's choice. It is a preference, not a secret, so the browser may read it. */
export const LOCALE_COOKIE = "op_locale";

/** BCP 47 tags for `Intl` formatting. English uses day-month order, as the product targets EU stores. */
export const INTL_TAGS: Record<Locale, string> = { en: "en-GB", pl: "pl-PL" };

export function isLocale(value: unknown): value is Locale {
  return typeof value === "string" && (LOCALES as readonly string[]).includes(value);
}

export function resolveLocale(value: string | undefined): Locale {
  return isLocale(value) ? value : DEFAULT_LOCALE;
}
