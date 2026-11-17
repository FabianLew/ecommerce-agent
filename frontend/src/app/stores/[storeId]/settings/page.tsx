import { notFound } from "next/navigation";

import type { components } from "@/api/schema";
import { PageHeading } from "@/components/PageHeading";
import { formatInstant } from "@/i18n/format";
import type { Locale } from "@/i18n/locale";
import type { Messages } from "@/i18n/messages";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

import { ActionPermissionForm } from "./ActionPermissionForm";
import { AlertDeputyForm } from "./AlertDeputyForm";
import { MutationStopForm } from "./MutationStopForm";
import { ProfileForm } from "./ProfileForm";
import { StatusBadge } from "@/components/ui/StatusBadge";

export const dynamic = "force-dynamic";

type Profile = components["schemas"]["ProfileResponse"];
type StoreDetails = components["schemas"]["StoreResponse"];
type Stop = components["schemas"]["MutationStopResponse"];
type Permission = components["schemas"]["ActionPermissionResponse"];
type AlertSetting = components["schemas"]["AlertSettingResponse"];

/**
 * Settings: the mutation stop (B-40, ADR-0020), the ad pause permission (ADR-0025), who is told by e-mail
 * (ADR-0026) and the Store Business Profile (B-33, ADR-0018).
 */
export default async function SettingsPage({ params }: { params: Promise<{ storeId: string }> }) {
  const { storeId } = await params;
  const path = storePath(storeId, "settings");
  const api = await backend(path);
  const [store, profile, stop, permission, alerts, locale, messages] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/business-profile", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/mutation-stop", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/action-permissions/{actionType}", {
      params: { path: { storeId, actionType: "PAUSE_AD" } },
    }),
    api.GET("/api/v1/stores/{storeId}/alert-settings", { params: { path: { storeId } } }),
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
      <div className="settings-layout">
      <nav className="settings-nav" aria-label={messages.settingsNav.label}>
        <ul>
          <li><a href="#settings-store">{messages.store.summary}</a></li>
          {stop.data ? <li><a href="#settings-stop">{messages.mutationStop.title}</a></li> : null}
          {permission.data ? <li><a href="#settings-ads">{messages.actionPermission.title}</a></li> : null}
          {alerts.data ? <li><a href="#settings-alerts">{messages.alertDeputy.title}</a></li> : null}
          <li><a href="#settings-profile">{t.title}</a></li>
        </ul>
      </nav>
      <div className="settings-sections">
      <div id="settings-store"><StoreSummary store={store.data} messages={messages} /></div>
      {stop.data ? (
        <div id="settings-stop">
          <MutationStopCard storeId={storeId} stop={stop.data} timezone={store.data.timezone} locale={locale}
            t={messages.mutationStop} />
        </div>
      ) : null}
      {permission.data ? (
        <div id="settings-ads">
          <ActionPermissionCard storeId={storeId} permission={permission.data} timezone={store.data.timezone}
            locale={locale} t={messages.actionPermission} />
        </div>
      ) : null}
      {alerts.data ? (
        <div id="settings-alerts"><AlertDeputyCard storeId={storeId} setting={alerts.data} t={messages.alertDeputy} /></div>
      ) : null}
      <section className="card" id="settings-profile" aria-labelledby="profile-title" data-testid="profile-card">
        <div className="card-header">
          <h2 id="profile-title">{t.title}</h2>
          {approved ? <StatusBadge tone="success">{t.approvedTitle(approved.version)}</StatusBadge> : null}
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
      </div>
      </div>
    </>
  );
}

/** The store and the user's access in it (moved here from the Overview in R7). */
function StoreSummary({ store, messages }: { store: StoreDetails; messages: Messages }) {
  const t = messages.store;
  return (
    <section className="card" aria-labelledby="store-summary" data-testid="store-summary">
      <h2 id="store-summary">{t.summary}</h2>
      <dl className="details">
        <dt>{t.name}</dt>
        <dd>{store.name}</dd>
        <dt>{t.organization}</dt>
        <dd>{store.organizationName}</dd>
        <dt>{t.currency}</dt>
        <dd>{store.currency}</dd>
        <dt>{t.timezone}</dt>
        <dd>{store.timezone}</dd>
        <dt>{t.roles}</dt>
        <dd>{store.roles.map((role) => t.roleLabels[role] ?? role).join(", ")}</dd>
        <dt>{t.permissions}</dt>
        <dd>{store.permissions.map((permission) => t.permissionLabels[permission] ?? permission).join(", ")}</dd>
      </dl>
    </section>
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
        <StatusBadge tone={stop.stopped || stop.globallyStopped ? "danger" : "success"}>
          {stop.stopped || stop.globallyStopped ? t.stopped : t.running}
        </StatusBadge>
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

function AlertDeputyCard({
  storeId,
  setting,
  t,
}: {
  storeId: string;
  setting: AlertSetting;
  t: Messages["alertDeputy"];
}) {
  const current = setting.candidates.find((candidate) => candidate.userId === setting.deputyUserId);
  return (
    <section className="card" aria-labelledby="alert-deputy-title" data-testid="alert-deputy-card">
      <div className="card-header">
        <h2 id="alert-deputy-title">{t.title}</h2>
      </div>
      <p className="muted">{t.intro}</p>
      <dl className="details">
        <dt>{t.deputy}</dt>
        <dd data-testid="alert-deputy-current">
          {setting.deputyUserId ? (current?.displayName ?? t.unnamed) : t.none}
        </dd>
      </dl>
      {setting.deputyUserId && !setting.deputyActive ? (
        <p className="form-message error" data-testid="alert-deputy-inactive">{t.inactive}</p>
      ) : null}
      {setting.canChange ? (
        setting.candidates.length || setting.deputyUserId ? (
          <AlertDeputyForm key={setting.version} storeId={storeId} version={setting.version}
            deputy={setting.deputyActive ? setting.deputyUserId : undefined} candidates={setting.candidates} />
        ) : (
          <p className="muted">{t.noCandidates}</p>
        )
      ) : (
        <p className="muted">{t.readOnly}</p>
      )}
    </section>
  );
}

function ActionPermissionCard({
  storeId,
  permission,
  timezone,
  locale,
  t,
}: {
  storeId: string;
  permission: Permission;
  timezone: string;
  locale: Locale;
  t: Messages["actionPermission"];
}) {
  const executable = permission.effectiveLevel === "APPROVAL_REQUIRED";
  return (
    <section className="card" aria-labelledby="permission-title" data-testid="action-permission-card">
      <div className="card-header">
        <h2 id="permission-title">{t.title}</h2>
        <StatusBadge tone={executable ? "success" : "info"} data-testid="action-permission-effective">
          {t.effective}: {t.levels[permission.effectiveLevel].split(":")[0]}
        </StatusBadge>
      </div>
      <p className="muted">{t.intro}</p>
      <dl className="details">
        <dt>{t.level}</dt>
        <dd data-testid="action-permission-level">{t.levels[permission.level]}</dd>
        {permission.reason ? (
          <>
            <dt>{t.reason}</dt>
            <dd>{permission.reason}</dd>
          </>
        ) : null}
      </dl>
      {permission.caps.length ? (
        <>
          <h3 className="subheading">{t.capsTitle}</h3>
          <ul data-testid="action-permission-caps">
            {permission.caps.map((cap) => (
              <li key={cap.source}>{t.caps[cap.code] ?? cap.code}</li>
            ))}
          </ul>
        </>
      ) : null}
      {permission.changedAt ? (
        <p className="muted">{t.changedAt(formatInstant(permission.changedAt, timezone, locale))}</p>
      ) : null}
      {permission.canLower || permission.canRaise ? (
        <ActionPermissionForm key={permission.version} storeId={storeId} version={permission.version}
          level={permission.level} canRaise={permission.canRaise} />
      ) : (
        <p className="muted">{t.readOnly}</p>
      )}
    </section>
  );
}
