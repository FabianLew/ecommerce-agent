import { notFound } from "next/navigation";

import type { components } from "@/api/schema";
import { PageHeading } from "@/components/PageHeading";
import { formatInstant } from "@/i18n/format";
import type { Locale } from "@/i18n/locale";
import type { Messages } from "@/i18n/messages";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

import { MutationStopForm } from "./MutationStopForm";
import { ProfileForm } from "./ProfileForm";

export const dynamic = "force-dynamic";

type Profile = components["schemas"]["ProfileResponse"];
type Stop = components["schemas"]["MutationStopResponse"];

/** Settings: the mutation stop (B-40, ADR-0020) and the Store Business Profile (B-33, ADR-0018). */
export default async function SettingsPage({ params }: { params: Promise<{ storeId: string }> }) {
  const { storeId } = await params;
  const path = storePath(storeId, "settings");
  const api = await backend(path);
  const [store, profile, stop, locale, messages] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/business-profile", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/mutation-stop", { params: { path: { storeId } } }),
    getLocale(),
    getMessages(),
  ]);
  redirectIfUnauthenticated(profile.response.status, path);
  if (!store.data || !profile.data) {
    notFound();
  }
  const t = messages.profile;
  const { approved, draft, canEdit } = profile.data;

  return (
    <>
      <PageHeading>{messages.sections.settings}</PageHeading>
      {stop.data ? (
        <MutationStopCard storeId={storeId} stop={stop.data} timezone={store.data.timezone} locale={locale}
          t={messages.mutationStop} />
      ) : null}
      <section className="card" aria-labelledby="profile-title" data-testid="profile-card">
        <div className="card-header">
          <h2 id="profile-title">{t.title}</h2>
          {approved ? <span className="badge badge-connected">{t.approvedTitle(approved.version)}</span> : null}
        </div>
        <p className="muted">{t.intro}</p>
        {approved ? (
          <ProfileSummary profile={approved} timezone={store.data.timezone} locale={locale} t={t} />
        ) : (
          <p className="muted" data-testid="profile-none">{t.none}</p>
        )}
        {canEdit ? (
          <>
            <h3 className="subheading">{draft ? t.draftTitle(draft.version) : t.newDraft}</h3>
            {draft ? <Missing profile={draft} t={t} /> : null}
            <ProfileForm storeId={storeId} draft={draft} approved={approved} currency={store.data.currency} />
          </>
        ) : (
          <p className="muted">{t.readOnly}</p>
        )}
      </section>
    </>
  );
}

function Missing({ profile, t }: { profile: Profile; t: Messages["profile"] }) {
  return profile.missingFields.length ? (
    <p className="form-message warning" data-testid="profile-missing">
      {t.missing}: {profile.missingFields.map((field) => t.missingNames[field] ?? field).join(", ")}
    </p>
  ) : null;
}

function ProfileSummary({
  profile,
  timezone,
  locale,
  t,
}: {
  profile: Profile;
  timezone: string;
  locale: Locale;
  t: Messages["profile"];
}) {
  return (
    <>
      <dl className="details" data-testid="profile-approved">
        <dt>{t.goal}</dt>
        <dd>{profile.goal ? t.goals[profile.goal] : t.notSet}</dd>
        <dt>{t.horizon}</dt>
        <dd>{profile.planningHorizonDays ?? t.notSet}</dd>
        <dt>{t.risk}</dt>
        <dd>{profile.riskPreference ? t.risks[profile.riskPreference] : t.notSet}</dd>
        <dt>{t.priorities}</dt>
        <dd>{profile.priorities.map((priority) => t.priorityNames[priority]).join(", ") || t.notSet}</dd>
        <dt>{t.plannedSpendPeriod}</dt>
        <dd>
          {profile.plannedAdSpend
            ? `${new Intl.NumberFormat(locale, { style: "currency", currency: profile.plannedAdSpend.currency }).format(
                profile.plannedAdSpend.amountMinor / 100,
              )} · ${profile.plannedAdSpend.period}`
            : t.notSet}
        </dd>
      </dl>
      <p className="muted">{t.approvedAt(formatInstant(profile.approvedAt, timezone, locale))}</p>
      <Missing profile={profile} t={t} />
    </>
  );
}

function MutationStopCard({
  storeId,
  stop,
  timezone,
  locale,
  t,
}: {
  storeId: string;
  stop: Stop;
  timezone: string;
  locale: Locale;
  t: Messages["mutationStop"];
}) {
  const allowed = stop.stopped ? stop.canResume : stop.canStop;
  return (
    <section className="card" aria-labelledby="stop-title" data-testid="mutation-stop-card">
      <div className="card-header">
        <h2 id="stop-title">{t.title}</h2>
        <span className={`badge ${stop.stopped || stop.globallyStopped ? "badge-error" : "badge-connected"}`}>
          {stop.stopped || stop.globallyStopped ? t.stopped : t.running}
        </span>
      </div>
      <p className="muted">{t.intro}</p>
      {stop.globallyStopped ? <p className="form-message warning">{t.globallyStopped}</p> : null}
      {stop.stopped && stop.reason ? (
        <dl className="details" data-testid="mutation-stop-reason">
          <dt>{t.reason}</dt>
          <dd>{stop.reason}</dd>
        </dl>
      ) : null}
      {stop.changedAt ? <p className="muted">{t.changedAt(formatInstant(stop.changedAt, timezone, locale))}</p> : null}
      {allowed ? (
        <MutationStopForm key={stop.version} storeId={storeId} version={stop.version} stopped={stop.stopped} />
      ) : (
        <p className="muted">{stop.stopped ? t.resumeReadOnly : t.readOnly}</p>
      )}
    </section>
  );
}
