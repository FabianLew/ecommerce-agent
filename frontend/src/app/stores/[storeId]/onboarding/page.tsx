import { Check } from "lucide-react";
import Link from "next/link";
import { notFound } from "next/navigation";

import type { components } from "@/api/schema";
import { PageHeading } from "@/components/PageHeading";
import { Callout } from "@/components/ui/Callout";
import { Icon } from "@/components/ui/Icon";
import { StatusBadge } from "@/components/ui/StatusBadge";
import type { Tone } from "@/components/ui/tones";
import { formatInstant } from "@/i18n/format";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

import { startMonitoring, updateOnboarding } from "./actions";

export const dynamic = "force-dynamic";

type StepCode = components["schemas"]["OnboardingResponse"]["nextStep"];
type Readiness = components["schemas"]["DomainResponse"]["readiness"];

const READINESS_TONE: Record<Readiness, Tone> = {
  READY: "success",
  IN_PROGRESS: "info",
  NOT_STARTED: "neutral",
  SKIPPED: "neutral",
  ERROR: "danger",
};

/** Blockers that describe work in progress rather than something to fix. */
const INFO_BLOCKERS = new Set(["IMPORT_RUNNING", "MONITORING_NOT_STARTED"]);

/**
 * Store setup (plan 4.D, 04 §12): a stepper with the four backend steps and one panel for the selected step. Done,
 * blockers and readiness come only from the backend; a submitted form never marks a step done.
 */
export default async function OnboardingPage({
  params,
  searchParams,
}: {
  params: Promise<{ storeId: string }>;
  searchParams: Promise<{ step?: string }>;
}) {
  const [{ storeId }, query] = await Promise.all([params, searchParams]);
  const path = `/stores/${encodeURIComponent(storeId)}/onboarding`;
  const api = await backend(path);
  const [state, store, locale, messages] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}/onboarding", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } }),
    getLocale(),
    getMessages(),
  ]);
  redirectIfUnauthenticated(state.response.status, path);
  if (!state.data || !store.data) {
    notFound();
  }
  const t = messages.onboarding;
  const data = state.data;
  const acks = data.acknowledgements;
  const monitoring = data.monitoring;
  const zone = store.data.timezone;
  const doneCount = data.steps.filter((step) => step.complete).length;
  const earlierDone = data.steps.filter((step) => step.step !== "START_MONITORING").every((step) => step.complete);
  const selected = data.steps.find((step) => step.step === query.step) ?? data.steps.find((step) => step.step === data.nextStep) ?? data.steps[0];
  const toggle = (ack: "SKIP_META" | "MAPPINGS_LATER") =>
    updateOnboarding.bind(
      null,
      storeId,
      data.currentStep,
      acks.includes(ack) ? acks.filter((item) => item !== ack) : [...acks, ack],
      data.version,
    );
  const integrations = storePath(storeId, "integrations");
  const primaryLink: Partial<Record<StepCode, string>> = {
    CONNECT_STORE: integrations,
    CONNECT_TOOLS: integrations,
    BUSINESS_DATA: storePath(storeId, "settings"),
  };

  return (
    <>
      <header className="page-header">
        <PageHeading>{t.title}</PageHeading>
        <p className="page-sub">{t.intro}</p>
      </header>

      {monitoring.startedAt ? (
        <Callout tone="success" title={t.doneTitle} role="status" data-testid="monitoring-started">
          <p>{t.doneText(formatInstant(monitoring.startedAt, zone, locale))}</p>
          <Link href={storePath(storeId)} className="button secondary">{t.goToOverview}</Link>
        </Callout>
      ) : null}

      <div className="setup-layout">
          <nav className="card setup-stepper" aria-label={t.stepsLabel}>
            <p className="setup-progress">
              <strong>{t.progress(doneCount, data.steps.length)}</strong>
            </p>
            <div className="progress-track" aria-hidden="true">
              <span style={{ width: `${(doneCount / Math.max(1, data.steps.length)) * 100}%` }} />
            </div>
            <ol className="stepper" data-testid="onboarding-steps">
              {data.steps.map((step, index) => {
                const state = step.complete ? "done" : step.step === data.nextStep ? "current" : "todo";
                return (
                  <li key={step.step} data-state={state} data-testid={`onboarding-${step.step}`}>
                    <Link
                      href={`${path}?step=${step.step}`}
                      className="stepper-link"
                      aria-current={step.step === selected?.step ? "step" : undefined}
                    >
                      <span className="stepper-marker" aria-hidden="true">
                        {step.complete ? <Icon icon={Check} size="sm" /> : index + 1}
                      </span>
                      <span className="stepper-text">
                        <span className="stepper-title">{t.steps[step.step]}</span>
                        <span className="stepper-state" data-testid="step-status">
                          {step.complete ? t.done : step.step === data.nextStep ? t.next : t.todo}
                        </span>
                      </span>
                    </Link>
                  </li>
                );
              })}
            </ol>
          </nav>

          <section className="card setup-readiness" aria-labelledby="readiness-title" data-testid="onboarding-readiness">
            <h2 id="readiness-title">{t.domainsTitle}</h2>
            <ul className="readiness-list">
              {data.domains.map((domain) => (
                <li key={domain.domain}>
                  <span>{t.domains[domain.domain]}</span>
                  <StatusBadge tone={READINESS_TONE[domain.readiness] ?? "neutral"} data-testid={`readiness-${domain.domain}`}>
                    {t.readiness[domain.readiness]}
                  </StatusBadge>
                  {domain.domain === "AD_MAPPINGS" && domain.detail ? (
                    <span className="readiness-sub">{t.verifiedMappings(domain.detail)}</span>
                  ) : null}
                </li>
              ))}
            </ul>
          </section>

        {selected ? (
          <section className="card setup-panel" aria-labelledby="step-title" data-testid="step-panel">
            <p className="setup-eyebrow">
              {t.stepNumber(data.steps.indexOf(selected) + 1)}
              {selected.step === data.nextStep && !selected.complete ? ` · ${t.current}` : ""}
            </p>
            <div className="card-header">
              <h2 id="step-title">{t.steps[selected.step]}</h2>
              {selected.complete ? <StatusBadge tone="success">{t.done}</StatusBadge> : null}
            </div>

            <div className="setup-facts">
              <div>
                <h3 className="setup-label">{t.why}</h3>
                <p>{t.purpose[selected.step]}</p>
              </div>
              <div>
                <h3 className="setup-label">{t.checks}</h3>
                <p>{t.checked[selected.step]}</p>
              </div>
            </div>

            {selected.blockers.map((blocker) => (
              <Callout key={blocker} tone={INFO_BLOCKERS.has(blocker) ? "info" : "warning"} role="note">
                {t.blockers[blocker] ?? blocker}
              </Callout>
            ))}
            {selected.complete && selected.blockers.length === 0 ? (
              <p className="muted">{t.stepDoneText}</p>
            ) : null}

            {selected.step === "START_MONITORING" ? (
              <div className="setup-scope">
                <h3 className="setup-label">{t.scopeTitle}</h3>
                <dl className="details" data-testid="monitoring-scope">
                  <dt>{t.scopeStore}</dt>
                  <dd>{t.readiness.READY}</dd>
                  <dt>{t.scopeAds}</dt>
                  <dd data-testid="monitoring-ads">{t.adsScope[monitoring.ads]}</dd>
                </dl>
                <p className="muted">{t.adsConsent}</p>
              </div>
            ) : null}

            {data.canEdit ? (
              <div className="setup-actions">
                {selected.step === "START_MONITORING" && !monitoring.startedAt ? (
                  earlierDone ? (
                    <form action={startMonitoring.bind(null, storeId, data.version)}>
                      <button type="submit" className="button primary" data-testid="start-monitoring">
                        {t.startMonitoring}
                      </button>
                    </form>
                  ) : (
                    <>
                      <button type="button" className="button primary" aria-disabled="true"
                        aria-describedby="start-disabled" data-testid="start-monitoring-disabled">
                        {t.startMonitoring}
                      </button>
                      <p id="start-disabled" className="muted">{t.startDisabled}</p>
                    </>
                  )
                ) : null}
                {selected.step !== "START_MONITORING" ? (
                  <Link className={selected.complete ? "button secondary" : "button primary"}
                    href={primaryLink[selected.step] ?? path}>
                    {t.actions[selected.step as keyof typeof t.actions]}
                  </Link>
                ) : null}
                {selected.step === "BUSINESS_DATA" ? (
                  <Link className="button secondary" href={`${integrations}/ads`}>{t.confirmAds}</Link>
                ) : null}
              </div>
            ) : (
              <p className="muted">{t.readOnly}</p>
            )}

            {data.canEdit && selected.step === "CONNECT_TOOLS" ? (
              <form action={toggle("SKIP_META")} className="setup-secondary">
                <button type="submit" className="button ghost" data-testid="skip-meta">
                  {acks.includes("SKIP_META") ? t.undoSkipMeta : t.skipMeta}
                </button>
                {acks.includes("SKIP_META") ? null : <span className="muted">{t.skipMetaConsequence}</span>}
              </form>
            ) : null}
            {data.canEdit && selected.step === "BUSINESS_DATA" && !acks.includes("MAPPINGS_LATER") &&
              selected.blockers.includes("NO_VERIFIED_MAPPING") ? (
              <form action={toggle("MAPPINGS_LATER")} className="setup-secondary">
                <button type="submit" className="button ghost">{t.mappingsLater}</button>
                <span className="muted">{t.mappingsLaterConsequence}</span>
              </form>
            ) : null}
          </section>
        ) : null}
      </div>
    </>
  );
}
