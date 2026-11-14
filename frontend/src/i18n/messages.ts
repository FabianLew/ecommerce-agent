import { en, type Messages } from "./en";
import type { Locale } from "./locale";
import { pl } from "./pl";

export type { Messages };

const CATALOGS: Record<Locale, Messages> = { en, pl };

export function messagesFor(locale: Locale): Messages {
  return CATALOGS[locale];
}
