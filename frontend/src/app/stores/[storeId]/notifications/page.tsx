import Link from "next/link";
import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { formatInstant } from "@/i18n/format";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath } from "@/lib/store-path";

import { markNotificationRead } from "./actions";

export const dynamic = "force-dynamic";

/** In-app notifications (ADR-0021): rendered here from their kind and backend-set parameters, newest first. */
export default async function NotificationsPage({
  params,
  searchParams,
}: {
  params: Promise<{ storeId: string }>;
  searchParams: Promise<{ cursor?: string }>;
}) {
  const [{ storeId }, query, locale, messages] = await Promise.all([params, searchParams, getLocale(), getMessages()]);
  const t = messages.notifications;
  const path = `/stores/${encodeURIComponent(storeId)}/notifications`;
  const api = await backend(path);
  const [store, list] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/notifications", { params: { path: { storeId }, query: { cursor: query.cursor } } }),
  ]);
  redirectIfUnauthenticated(list.response.status, path);
  if (!list.data || !store.data) {
    notFound();
  }
  const timezone = store.data.timezone;

  return (
    <>
      <PageHeading>{t.title}</PageHeading>
      <p className="muted" data-testid="notifications-unread">{t.unread(list.data.unreadCount)}</p>
      {list.data.items.length === 0 ? (
        <StatePanel tone="info" title={t.empty} description="" />
      ) : (
        <ul className="plain-list card" data-testid="notification-list">
          {list.data.items.map((item) => {
            const p = item.parameters;
            const ad = p.adName ? `${p.adName} (${p.adExternalId ?? ""})` : (p.adExternalId ?? "—");
            return (
              <li key={item.id} className={item.read ? "muted" : undefined}>
                <span className={`badge${item.severity === "CRITICAL" ? " badge-error" : ""}`}>
                  {t.severity[item.severity] ?? item.severity}
                </span>{" "}
                {t.text(
                  item.kind,
                  ad,
                  p.sku ?? p.variantName ?? "—",
                  item.code ?? "—",
                  p.daysOfCover ?? "—",
                  p.warningDays ?? "—",
                )}{" "}
                <span className="muted">{formatInstant(item.createdAt, timezone, locale)}</span>{" "}
                {item.proposalId ? <Link href={decisionPath(storeId, item.proposalId)}>{t.open}</Link> : null}{" "}
                {item.read ? (
                  <span className="muted">· {t.read}</span>
                ) : (
                  <form action={markNotificationRead.bind(null, storeId, item.id)} className="inline-form">
                    <button type="submit" className="link-button">
                      {t.markRead}
                    </button>
                  </form>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {list.data.hasMore && list.data.nextCursor ? (
        <Link href={`${path}?cursor=${encodeURIComponent(list.data.nextCursor)}`} className="button secondary">
          {t.loadMore}
        </Link>
      ) : null}
    </>
  );
}
