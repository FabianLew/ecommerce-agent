import Link from "next/link";
import { notFound } from "next/navigation";

import type { components } from "@/api/schema";
import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { INTL_TAGS, type Locale } from "@/i18n/locale";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath, storePath } from "@/lib/store-path";

import { describe } from "./describe";

export const dynamic = "force-dynamic";

type Activity = components["schemas"]["ActivitySummary"];
type ActivityType = Activity["type"];

const TYPES: ActivityType[] = ["DETECTION", "RECOMMENDATION", "POLICY", "ACTION", "INTEGRATION", "SETTINGS"];

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
      <header className="page-header">
        <PageHeading>{t.title}</PageHeading>
        <p className="page-sub">{t.timezoneNote(data.meta.timezone)}</p>
      </header>
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
        <div className="card feed-card" data-testid="activity-list">
          {groupByDay(data.items, data.meta.timezone, locale).map(([day, items]) => (
            <section key={day} className="feed-day" aria-label={day}>
              <h2 className="feed-day-title">{day}</h2>
              <ul className="feed-list">
                {items.map((item) => {
                  const proposal = proposalLink(item);
                  return (
                    <li key={item.id} className="feed-row">
                      <div className="feed-main">
                        <Link href={`${path}/${encodeURIComponent(item.id)}`} className="feed-link">
                          {describe(item, messages)}
                        </Link>
                        <span className="feed-meta">
                          <span className="feed-type" data-type={item.type}>{t.types[item.type]}</span>
                          <span aria-hidden="true">·</span>
                          <span>{item.actor.type === "USER" ? (item.actor.displayName ?? t.you) : t.system}</span>
                        </span>
                      </div>
                      <div className="feed-side">
                        {proposal ? (
                          <Link href={decisionPath(storeId, proposal)} className="feed-extra">{t.openDecision}</Link>
                        ) : null}
                        <time dateTime={item.occurredAt} className="feed-time">
                          {formatTime(item.occurredAt, data.meta.timezone, locale)}
                        </time>
                      </div>
                    </li>
                  );
                })}
              </ul>
            </section>
          ))}
        </div>
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

/** Items grouped under their local day in the store's time zone, keeping the newest-first order. */
function groupByDay(items: Activity[], timezone: string, locale: Locale): [string, Activity[]][] {
  const format = new Intl.DateTimeFormat(INTL_TAGS[locale], { dateStyle: "full", timeZone: timezone });
  const groups = new Map<string, Activity[]>();
  for (const item of items) {
    const day = format.format(new Date(item.occurredAt));
    groups.set(day, [...(groups.get(day) ?? []), item]);
  }
  return [...groups.entries()];
}

function formatTime(value: string, timezone: string, locale: Locale): string {
  return new Intl.DateTimeFormat(INTL_TAGS[locale], { timeStyle: "short", timeZone: timezone }).format(new Date(value));
}

function proposalLink(item: Activity): string | undefined {
  if (item.resourceType === "decision_proposal" && item.resourceId) {
    return item.resourceId;
  }
  return item.details.proposalId;
}
