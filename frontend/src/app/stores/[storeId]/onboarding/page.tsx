import Link from "next/link";
import { notFound } from "next/navigation";
import { Fragment } from "react";

import { PageHeading } from "@/components/PageHeading";
import { getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

import { updateOnboarding } from "./actions";

export const dynamic = "force-dynamic";

/** Onboarding of an existing store (04 §12): steps and readiness come from the backend's view of the real state. */
export default async function OnboardingPage({ params }: { params: Promise<{ storeId: string }> }) {
  const { storeId } = await params;
  const path = `/stores/${encodeURIComponent(storeId)}/onboarding`;
  const api = await backend(path);
  const [state, messages] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}/onboarding", { params: { path: { storeId } } }),
    getMessages(),
  ]);
  redirectIfUnauthenticated(state.response.status, path);
  if (!state.data) {
    notFound();
  }
  const t = messages.onboarding;
  const data = state.data;
  const acks = data.acknowledgements;
  const links: Record<string, string> = {
    CONNECT_STORE: storePath(storeId, "integrations"),
    CONNECT_TOOLS: storePath(storeId, "integrations"),
    BUSINESS_DATA: storePath(storeId, "settings"),
  };
  const toggle = (ack: "SKIP_META" | "MAPPINGS_LATER") =>
    updateOnboarding.bind(
      null,
      storeId,
      data.currentStep,
      acks.includes(ack) ? acks.filter((item) => item !== ack) : [...acks, ack],
      data.version,
    );

  return (
    <>
      <PageHeading>{t.title}</PageHeading>
      <p className="muted">{t.intro}</p>
      <ol className="stack" data-testid="onboarding-steps">
        {data.steps.map((step) => (
          <li key={step.step} className="card" data-testid={`onboarding-${step.step}`}>
            <div className="card-header">
              <h2>{t.steps[step.step]}</h2>
              <span className={`badge ${step.complete ? "badge-connected" : ""}`} data-testid="step-status">
                {step.complete ? t.done : step.step === data.nextStep ? t.next : t.todo}
              </span>
            </div>
            {step.blockers.map((blocker) => (
              <p key={blocker} className="form-message warning">{t.blockers[blocker] ?? blocker}</p>
            ))}
            <div className="form-actions inline-form">
              {step.step in links ? (
                <Link className="button secondary" href={links[step.step] ?? path}>{t.open}</Link>
              ) : null}
              {step.step === "BUSINESS_DATA" ? (
                <Link className="button secondary" href={`${storePath(storeId, "integrations")}/ads`}>
                  {messages.adMappings.title}
                </Link>
              ) : null}
              {data.canEdit && step.step === "CONNECT_TOOLS" ? (
                <form action={toggle("SKIP_META")}>
                  <button type="submit" className="button secondary" data-testid="skip-meta">
                    {acks.includes("SKIP_META") ? t.undoSkipMeta : t.skipMeta}
                  </button>
                </form>
              ) : null}
              {data.canEdit && step.step === "BUSINESS_DATA" && !acks.includes("MAPPINGS_LATER") ? (
                <form action={toggle("MAPPINGS_LATER")}>
                  <button type="submit" className="button secondary">{t.mappingsLater}</button>
                </form>
              ) : null}
            </div>
          </li>
        ))}
      </ol>
      <section className="card" aria-labelledby="readiness-title" data-testid="onboarding-readiness">
        <h2 id="readiness-title">{t.domainsTitle}</h2>
        <dl className="details">
          {data.domains.map((domain) => (
            <Fragment key={domain.domain}>
              <dt>{t.domains[domain.domain]}</dt>
              <dd data-testid={`readiness-${domain.domain}`}>
                {t.readiness[domain.readiness]}
                {domain.domain === "AD_MAPPINGS" && domain.detail ? ` · ${t.verifiedMappings(domain.detail)}` : ""}
              </dd>
            </Fragment>
          ))}
        </dl>
      </section>
      {data.canEdit ? null : <p className="muted">{t.readOnly}</p>}
    </>
  );
}
