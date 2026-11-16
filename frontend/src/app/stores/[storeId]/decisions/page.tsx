import Link from "next/link";
import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { PROPOSAL_STATUS_TONE, toneOf } from "@/components/ui/tones";
import { formatInstant, formatRelative } from "@/i18n/format";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath, storePath } from "@/lib/store-path";

import { coverText, GROUPS, isGroup, label, problemTitle, recommendationText } from "./labels";

export const dynamic = "force-dynamic";

/**
 * Decision Inbox (04 §5, plan 4.B): three groups with counts and one row per case, newest first. A row reads as
 * problem, recommendation, status and deadline; the whole row opens the decision.
 */
export default async function DecisionsPage({
  params,
  searchParams,
}: {
  params: Promise<{ storeId: string }>;
  searchParams: Promise<{ group?: string; cursor?: string }>;
}) {
  const [{ storeId }, query, locale, messages] = await Promise.all([params, searchParams, getLocale(), getMessages()]);
  const t = messages.decisions;
  const group = isGroup(query.group) ? query.group : "TO_APPROVE";
  const path = storePath(storeId, "decisions");
  const api = await backend(path);
  const { data, response } = await api.GET("/api/v1/stores/{storeId}/decisions", {
    params: { path: { storeId }, query: { group, cursor: query.cursor } },
  });
  redirectIfUnauthenticated(response.status, path);
  if (response.status === 400) {
    // A stale or foreign cursor: start the group again instead of failing the page.
    return (
      <>
        <PageHeading>{messages.sections.decisions}</PageHeading>
        <Link href={`${path}?group=${group}`} className="button primary">{t.backToList}</Link>
      </>
    );
  }
  if (!data) {
    notFound();
  }
  const counts = { TO_APPROVE: data.counts.toApprove, MONITORING: data.counts.monitoring, COMPLETED: data.counts.completed };
  const timezone = data.meta.timezone;
  const now = new Date();
  const pending = group === "TO_APPROVE";

  return (
    <>
      <PageHeading>{messages.sections.decisions}</PageHeading>
      <nav className="tabs" aria-label={t.groupsLabel}>
        {GROUPS.map((item) => (
          <Link
            key={item}
            href={`${path}?group=${item}`}
            className="tab"
            aria-current={item === group ? "page" : undefined}
            data-testid={`decision-group-${item}`}
          >
            {t.groups[item]} <span className="tab-count">{counts[item]}</span>
          </Link>
        ))}
      </nav>
      {data.items.length === 0 ? (
        <StatePanel tone="info" title={t.emptyTitle[group]} description={t.emptyDescription[group]} />
      ) : (
        <div className="card inbox">
          <div className="inbox-head" aria-hidden="true">
            <span>{t.problem}</span>
            <span>{t.recommendation}</span>
            <span>{t.status}</span>
            <span>{pending ? t.decideBy : t.when}</span>
          </div>
          <ul className="inbox-list" data-testid="decision-list" aria-label={t.listLabel}>
            {data.items.map((item) => {
              const expired = new Date(item.expiresAt).getTime() < now.getTime();
              return (
                <li key={item.proposalId} className="inbox-row">
                  <div className="inbox-problem">
                    <Link href={decisionPath(storeId, item.proposalId)} className="inbox-link">
                      {problemTitle(item, t)}
                    </Link>
                    <span className="inbox-sub">
                      {item.facts?.daysOfCover === undefined || item.facts?.daysOfCover === null ? (
                        <>{t.cover}: <span data-testid="decision-cover">{t.coverUnknown}</span></>
                      ) : (
                        <>{t.stockLasts("")}<span data-testid="decision-cover">{coverText(item, messages, locale)}</span></>
                      )}
                    </span>
                  </div>
                  <div className="inbox-cell">
                    <span className="inbox-label">{t.recommendation}</span>
                    <span data-testid="decision-recommendation">{recommendationText(item, t)}</span>
                  </div>
                  <div className="inbox-cell">
                    <span className="inbox-label">{t.status}</span>
                    <StatusBadge tone={toneOf(PROPOSAL_STATUS_TONE, item.status)}>{t.proposalStatus[item.status]}</StatusBadge>
                    {item.policyOutcome ? (
                      <span className="inbox-sub">{label(t.policyOutcome, item.policyOutcome)}</span>
                    ) : null}
                  </div>
                  <div className="inbox-cell">
                    <span className="inbox-label">{pending ? t.decideBy : t.when}</span>
                    {pending ? (
                      <>
                        <span className={expired ? "inbox-deadline expired" : "inbox-deadline"}>
                          {expired ? t.expiredAgo(formatRelative(item.expiresAt, locale, now)) : formatRelative(item.expiresAt, locale, now)}
                        </span>
                        <span className="inbox-sub">{formatInstant(item.expiresAt, timezone, locale)}</span>
                      </>
                    ) : (
                      <span>{formatInstant(item.createdAt, timezone, locale)}</span>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      )}
      {data.hasMore && data.nextCursor ? (
        <Link
          href={`${path}?group=${group}&cursor=${encodeURIComponent(data.nextCursor)}`}
          className="button secondary"
        >
          {t.loadMore}
        </Link>
      ) : null}
    </>
  );
}
