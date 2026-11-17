import Link from "next/link";
import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { formatInstant } from "@/i18n/format";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath, storePath } from "@/lib/store-path";

import { describe } from "../describe";

export const dynamic = "force-dynamic";

/** Activity Detail (04 §9): one recorded event, who did it, and links to its decision and execution. */
export default async function ActivityDetailPage({
  params,
}: {
  params: Promise<{ storeId: string; activityId: string }>;
}) {
  const [{ storeId, activityId }, locale, messages] = await Promise.all([params, getLocale(), getMessages()]);
  const t = messages.activityDetail;
  const list = storePath(storeId, "activity");
  const path = `${list}/${encodeURIComponent(activityId)}`;
  const api = await backend(path);
  const { data, response } = await api.GET("/api/v1/stores/{storeId}/activities/{activityId}", {
    params: { path: { storeId, activityId } },
  });
  redirectIfUnauthenticated(response.status, path);
  if (!data) {
    notFound();
  }
  const item = data.activity;
  const actor =
    item.actor.type === "USER" ? (item.actor.displayName ?? messages.activity.you) : messages.activity.system;

  return (
    <>
      <header className="page-header">
        <p className="setup-eyebrow">{t.title} · {messages.activity.types[item.type]}</p>
        <PageHeading>{describe(item, messages)}</PageHeading>
        <p className="page-sub">{formatInstant(item.occurredAt, data.meta.timezone, locale)} · {actor}</p>
      </header>
      <section className="card stack-card" data-testid="activity-detail">
        <dl className="details">
          <dt>{t.what}</dt>
          <dd>{describe(item, messages)}</dd>
          <dt>{t.when}</dt>
          <dd>{formatInstant(item.occurredAt, data.meta.timezone, locale)}</dd>
          <dt>{t.who}</dt>
          <dd>{actor}</dd>
        </dl>
        {data.proposalId ? (
          <div>
            <Link href={decisionPath(storeId, data.proposalId)} className="button secondary">
              {data.executionId ? t.openExecution : messages.activity.openDecision}
            </Link>
          </div>
        ) : null}
      </section>
      {item.resourceType || Object.keys(item.details).length > 0 ? (
        <details className="card technical-card">
          <summary>{t.details}</summary>
          <dl className="details">
            {item.resourceType ? (
              <>
                <dt>{t.resource}</dt>
                <dd><code>{item.resourceType}/{item.resourceId}</code></dd>
              </>
            ) : null}
            {Object.entries(item.details).map(([key, value]) => (
              <div key={key} className="details-row">
                <dt><code>{key}</code></dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
        </details>
      ) : null}
    </>
  );
}
