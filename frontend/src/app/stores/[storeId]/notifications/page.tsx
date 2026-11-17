import Link from "next/link";
import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { Icon } from "@/components/ui/Icon";
import { TONE_ICON } from "@/components/ui/StatusBadge";
import { SEVERITY_TONE, toneOf } from "@/components/ui/tones";
import { formatDays, formatInstant } from "@/i18n/format";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath } from "@/lib/store-path";

import { markNotificationRead } from "./actions";

export const dynamic = "force-dynamic";

/**
 * In-app notifications (ADR-0021), newest first: severity as icon + text, the message, when, and what to do next.
 * The texts are rendered here from the kind and the backend-set parameters; numbers go through the formatters.
 */
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
  const days = (value: string | undefined) => (value ? formatDays(value, locale, messages.common.dayForms) : "—");

  return (
    <>
      <PageHeading>{t.title}</PageHeading>
      <p className="muted" data-testid="notifications-unread">{t.unread(list.data.unreadCount)}</p>
      {list.data.items.length === 0 ? (
        <StatePanel tone="info" title={t.empty} description={t.emptyDescription} />
      ) : (
        <ul className="card notification-list" data-testid="notification-list">
          {list.data.items.map((item) => {
            const p = item.parameters;
            const ad = p.adName ? `${p.adName} (${p.adExternalId ?? ""})` : (p.adExternalId ?? "—");
            const product = p.variantName && p.sku ? `${p.variantName} (${p.sku})` : (p.variantName ?? p.sku ?? "—");
            const tone = toneOf(SEVERITY_TONE, item.severity);
            return (
              <li key={item.id} className="notification-row" data-read={item.read}>
                <span className={`notification-icon tone-${tone}`} aria-hidden="true">
                  <Icon icon={TONE_ICON[tone]} />
                </span>
                <div className="notification-body">
                  <p className="notification-text">
                    {item.read ? null : <span className="visually-hidden">{t.unreadLabel}: </span>}
                    {t.text(item.kind, ad, product, item.code ?? "—", days(p.daysOfCover), days(p.warningDays))}
                  </p>
                  <p className="notification-meta">
                    <span>{t.severity[item.severity] ?? item.severity}</span>
                    <span aria-hidden="true">·</span>
                    <time dateTime={item.createdAt}>{formatInstant(item.createdAt, timezone, locale)}</time>
                    {item.read ? (
                      <>
                        <span aria-hidden="true">·</span>
                        <span>{t.read}</span>
                      </>
                    ) : null}
                  </p>
                </div>
                <div className="notification-actions">
                  {item.proposalId ? (
                    <Link href={decisionPath(storeId, item.proposalId)} className="button secondary sm">
                      {t.open}
                    </Link>
                  ) : null}
                  {item.read ? null : (
                    <form action={markNotificationRead.bind(null, storeId, item.id)}>
                      <button type="submit" className="button ghost sm">{t.markRead}</button>
                    </form>
                  )}
                </div>
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
