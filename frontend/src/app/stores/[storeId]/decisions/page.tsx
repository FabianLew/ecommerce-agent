import Link from "next/link";
import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { formatInstant } from "@/i18n/format";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath, storePath } from "@/lib/store-path";

import { coverText, GROUPS, isGroup, label, problemTitle, recommendationText } from "./labels";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { PROPOSAL_STATUS_TONE, toneOf } from "@/components/ui/tones";

export const dynamic = "force-dynamic";

/** Decision Inbox (04 §5): three groups, one row per episode, newest first. */
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
        <ul className="decision-list" data-testid="decision-list">
          {data.items.map((item) => (
            <li key={item.proposalId} className="card decision-row">
              <div className="card-header">
                <h2>
                  <Link href={decisionPath(storeId, item.proposalId)}>{problemTitle(item, t)}</Link>
                </h2>
                <StatusBadge tone={toneOf(PROPOSAL_STATUS_TONE, item.status)}>{t.proposalStatus[item.status]}</StatusBadge>
              </div>
              <dl className="details">
                <dt>{t.recommendation}</dt>
                <dd data-testid="decision-recommendation">{recommendationText(item, t)}</dd>
                <dt>{t.cover}</dt>
                <dd data-testid="decision-cover">{coverText(item, t, locale)}</dd>
                {item.policyOutcome ? (
                  <>
                    <dt>{t.policy}</dt>
                    <dd>{label(t.policyOutcome, item.policyOutcome)}</dd>
                  </>
                ) : null}
                <dt>{t.created}</dt>
                <dd>{formatInstant(item.createdAt, timezone, locale)}</dd>
                <dt>{t.expires}</dt>
                <dd>{formatInstant(item.expiresAt, timezone, locale)}</dd>
              </dl>
            </li>
          ))}
        </ul>
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
