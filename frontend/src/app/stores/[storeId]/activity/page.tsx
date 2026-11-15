import Link from "next/link";
import { notFound } from "next/navigation";

import type { components } from "@/api/schema";
import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { formatInstant } from "@/i18n/format";
import type { Messages } from "@/i18n/messages";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath, storePath } from "@/lib/store-path";

import { label } from "../decisions/labels";

export const dynamic = "force-dynamic";

type Activity = components["schemas"]["ActivitySummary"];
type ActivityType = Activity["type"];

const TYPES: ActivityType[] = ["DETECTION", "RECOMMENDATION", "POLICY", "INTEGRATION"];

/** Activity History (04 §9): the store's operational history, newest first, with links to the decision. */
export default async function ActivityPage({
  params,
  searchParams,
}: {
  params: Promise<{ storeId: string }>;
  searchParams: Promise<{ type?: string; cursor?: string }>;
}) {
  const [{ storeId }, query, locale, messages] = await Promise.all([params, searchParams, getLocale(), getMessages()]);
  const t = messages.activity;
  const type = TYPES.find((item) => item === query.type);
  const path = storePath(storeId, "activity");
  const api = await backend(path);
  const { data, response } = await api.GET("/api/v1/stores/{storeId}/activities", {
    params: { path: { storeId }, query: { type, cursor: query.cursor } },
  });
  redirectIfUnauthenticated(response.status, path);
  if (response.status === 400) {
    return (
      <>
        <PageHeading>{t.title}</PageHeading>
        <Link href={path} className="button primary">{t.startOver}</Link>
      </>
    );
  }
  if (!data) {
    notFound();
  }
  const filterHref = (item?: ActivityType) => (item ? `${path}?type=${item}` : path);

  return (
    <>
      <PageHeading>{t.title}</PageHeading>
      <nav className="tabs" aria-label={t.filterLabel}>
        {[undefined, ...TYPES].map((item) => (
          <Link
            key={item ?? "ALL"}
            href={filterHref(item)}
            className="tab"
            aria-current={item === type ? "page" : undefined}
          >
            {item ? t.types[item] : t.all}
          </Link>
        ))}
      </nav>
      {data.items.length === 0 ? (
        <StatePanel tone="info" title={t.emptyTitle} description={t.emptyDescription} />
      ) : (
        <table className="table card" data-testid="activity-list">
          <tbody>
            {data.items.map((item) => (
              <tr key={item.id}>
                <td className="nowrap">{formatInstant(item.occurredAt, data.meta.timezone, locale)}</td>
                <td>
                  <span className="badge">{t.types[item.type]}</span>
                </td>
                <td>
                  {describe(item, messages)}
                  {proposalLink(item) ? (
                    <>
                      {" "}
                      <Link href={decisionPath(storeId, proposalLink(item)!)}>{t.openDecision}</Link>
                    </>
                  ) : null}
                </td>
                <td className="muted">
                  {item.actor.type === "USER" ? (item.actor.displayName ?? t.you) : t.system}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data.hasMore && data.nextCursor ? (
        <Link
          href={`${path}?${type ? `type=${type}&` : ""}cursor=${encodeURIComponent(data.nextCursor)}`}
          className="button secondary"
        >
          {t.loadMore}
        </Link>
      ) : null}
    </>
  );
}

/** A simple description from the action and its codes; unknown codes are shown as they are. */
function describe(item: Activity, messages: Messages): string {
  const t = messages.activity;
  const base = label(t.actions, item.action);
  const to = item.details.to;
  if (item.action === "OPPORTUNITY_STATUS_CHANGED" && to) {
    return `${base}: ${label(t.opportunityStatus, to)}`;
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

function proposalLink(item: Activity): string | undefined {
  if (item.resourceType === "decision_proposal" && item.resourceId) {
    return item.resourceId;
  }
  return item.details.proposalId;
}
