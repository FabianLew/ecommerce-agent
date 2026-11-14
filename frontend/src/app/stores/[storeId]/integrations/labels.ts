import type { components } from "@/api/schema";
import type { Messages } from "@/i18n/messages";

export { formatInstant } from "@/i18n/format";

export type Integration = components["schemas"]["IntegrationResponse"];
type SyncJob = NonNullable<Integration["lastSync"]>;

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
