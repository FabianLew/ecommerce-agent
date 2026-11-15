import type { components } from "@/api/schema";
import type { Locale } from "@/i18n/locale";
import { formatNumber } from "@/i18n/format";
import type { Messages } from "@/i18n/messages";

export type DecisionSummary = components["schemas"]["DecisionSummary"];
export type DecisionPresentation = components["schemas"]["DecisionPresentation"];
export type InboxGroup = DecisionSummary["group"];

export const GROUPS: InboxGroup[] = ["TO_APPROVE", "MONITORING", "COMPLETED"];

export function isGroup(value: string | undefined): value is InboxGroup {
  return GROUPS.includes(value as InboxGroup);
}

type Decision = Pick<DecisionSummary, "subject" | "action" | "disposition" | "facts">;

/** The problem in plain words: which product runs low (04 §5 row: the problem). */
export function problemTitle(decision: Decision, t: Messages["decisions"]): string {
  const { productName, variantName, sku } = decision.subject;
  const name = variantName && variantName !== productName ? variantName : (productName ?? sku ?? decision.subject.id);
  return t.lowStock(sku ? `${name} (${sku})` : name);
}

/** The recommendation: the proposed action, or the disposition when there is none. */
export function recommendationText(decision: Decision, t: Messages["decisions"]): string {
  if (decision.action?.type === "PAUSE_AD") {
    return t.pauseAd(decision.action.targetName ?? decision.action.targetExternalId ?? decision.action.targetId);
  }
  return t.disposition[decision.disposition];
}

export function coverText(decision: Decision, t: Messages["decisions"], locale: Locale): string {
  const days = decision.facts?.daysOfCover;
  return days === undefined || days === null ? t.coverUnknown : t.coverDays(formatNumber(days, locale));
}

/** A code through a catalog; an unknown code is shown as is rather than hidden. */
export function label(codes: Record<string, string>, code: string): string {
  return Object.hasOwn(codes, code) ? (codes[code] ?? code) : code;
}
