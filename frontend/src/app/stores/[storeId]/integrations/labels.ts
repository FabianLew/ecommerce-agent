import type { components } from "@/api/schema";
import { INTL_TAGS, type Locale } from "@/i18n/locale";
import type { Messages } from "@/i18n/messages";

export type Integration = components["schemas"]["IntegrationResponse"];
type SyncJob = NonNullable<Integration["lastSync"]>;

/** Formats an instant in the store's timezone (04 §2: times shown in store time) and the user's language. */
export function formatInstant(value: string | undefined, timezone: string, locale: Locale): string {
  if (!value) {
    return "—";
  }
  return new Intl.DateTimeFormat(INTL_TAGS[locale], { dateStyle: "medium", timeStyle: "short", timeZone: timezone })
    .format(new Date(value));
}

/** What the user can do about a finished import (ADR-0008). */
export function syncHelp(job: SyncJob, t: Messages["integrations"]): string | null {
  if (job.status === "INCOMPLETE") {
    return t.syncIncomplete;
  }
  if (job.status === "FAILED") {
    const code = job.errorCode as keyof typeof t.errorHelp | undefined;
    return code && Object.hasOwn(t.errorHelp, code) ? t.errorHelp[code] : t.syncFailedUnknown;
  }
  return null;
}
