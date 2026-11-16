import type { components } from "@/api/schema";
import type { Messages } from "@/i18n/messages";

import { label } from "../decisions/labels";

type Activity = components["schemas"]["ActivitySummary"];

/** A simple description from the action and its codes; unknown codes are shown as they are. */
export function describe(item: Activity, messages: Messages): string {
  const t = messages.activity;
  const base = label(t.actions, item.action);
  const to = item.details.to;
  if (item.action === "OPPORTUNITY_STATUS_CHANGED" && to) {
    return label(t.opportunityStatus, to);
  }
  if (item.action === "PROPOSAL_STATUS_CHANGED" && to) {
    return `${base}: ${label(messages.decisions.proposalStatus, to)}`;
  }
  if (item.action === "PROPOSAL_CREATED" && item.details.disposition) {
    return `${base}: ${label(messages.decisions.disposition, item.details.disposition)}`;
  }
  if (item.action === "POLICY_EVALUATED" && item.details.outcome) {
    return `${base}: ${label(messages.decisions.policyOutcome, item.details.outcome)}`;
  }
  return base;
}
