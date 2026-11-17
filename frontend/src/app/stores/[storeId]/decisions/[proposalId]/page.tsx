import { ArrowDown } from "lucide-react";
import Link from "next/link";
import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { Callout } from "@/components/ui/Callout";
import { Icon } from "@/components/ui/Icon";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { PROPOSAL_STATUS_TONE, type Tone, toneOf } from "@/components/ui/tones";
import { formatCount, formatDays, formatInstant, formatNumber, formatRelative } from "@/i18n/format";
import type { Messages } from "@/i18n/messages";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath } from "@/lib/store-path";

import { coverText, label, problemTitle, recommendationText } from "../labels";
import { DecisionCommands } from "./DecisionCommands";
import { evidenceValue } from "./evidence-value";
import { ExecutionTimeline } from "./ExecutionTimeline";

export const dynamic = "force-dynamic";

const CHECK_TONE: Record<string, Tone> = { PASSED: "success", FAILED: "danger", NOT_EVALUATED: "neutral" };
const CHECK_ORDER: Record<string, number> = { FAILED: 0, NOT_EVALUATED: 1, PASSED: 2 };

/**
 * Decision Detail (04 §5, plan 4.C): what is happening, why it matters, the recommendation with its exact scope, what
 * followed, the evidence and the safety checks, with the decision panel beside them on desktop. Blocking reasons,
 * failed checks and the action scope are always visible; technical data sits in a disclosure. Approve and reject use
 * the same `DecisionCommands` (dialogs, idempotency keys, ETag) as before.
 */
export default async function DecisionDetailPage({
  params,
}: {
  params: Promise<{ storeId: string; proposalId: string }>;
}) {
  const [{ storeId, proposalId }, locale, messages] = await Promise.all([params, getLocale(), getMessages()]);
  const t = messages.decisions;
  const d = t.detail;
  const path = decisionPath(storeId, proposalId);
  const api = await backend(path);
  const pathParams = { path: { storeId, proposalId } };
  const [detail, evidence] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}/decisions/{proposalId}", { params: pathParams }),
    api.GET("/api/v1/stores/{storeId}/decisions/{proposalId}/evidence", { params: pathParams }),
  ]);
  redirectIfUnauthenticated(detail.response.status, path);
  if (!detail.data) {
    notFound();
  }
  const decision = detail.data;
  const timezone = decision.meta.timezone;
  const at = (value: string | undefined | null) => formatInstant(value, timezone, locale);
  const facts = decision.facts;
  const blocked = decision.blockedReason;
  const etag = detail.response.headers.get("ETag") ?? "";
  const pending = decision.action !== undefined && decision.action !== null && decision.status === "PROPOSED";
  const canApprove = decision.allowedActions.includes("APPROVE");
  const canReject = decision.allowedActions.includes("REJECT");
  const deadline = d.decideBy(at(decision.expiresAt), formatRelative(decision.expiresAt, locale));
  const shortDeadline = d.decideIn(formatRelative(decision.expiresAt, locale));
  const checks = [...(decision.policyEvaluation?.checks ?? [])].sort(
    (a, b) => (CHECK_ORDER[a.result] ?? 1) - (CHECK_ORDER[b.result] ?? 1),
  );
  const passed = checks.filter((check) => check.result === "PASSED").length;
  const daysUnknown = facts.daysOfCover === undefined || facts.daysOfCover === null;

  return (
    <>
      <header className="decision-header">
        <PageHeading>{problemTitle(decision, t)}</PageHeading>
        <p className="decision-meta">
          <StatusBadge tone={toneOf(PROPOSAL_STATUS_TONE, decision.status)} data-testid="decision-status">
            {t.proposalStatus[decision.status]}
          </StatusBadge>
          <StatusBadge tone="neutral" icon={false}>{t.disposition[decision.disposition]}</StatusBadge>
          {decision.revision > 1 ? <span>{t.revision(decision.revision)}</span> : null}
          <span>{decision.status === "PROPOSED" ? deadline : d.decidedOn(at(decision.createdAt))}</span>
        </p>
      </header>

      <div className="decision-layout">
        <div className="decision-main" data-testid="decision-summary">
          <section className="card" aria-labelledby="happening-title">
            <h2 id="happening-title">{d.happening}</h2>
            <div className="fact-tiles">
              <div className="fact-tile">
                <p className="fact-label">{d.available}</p>
                <p className="fact-value">{facts.availableToSell ?? "—"}</p>
                <p className="fact-sub">{d.stockConfirmed(at(facts.stockConfirmedAt))}</p>
              </div>
              <div className="fact-tile">
                <p className="fact-label">{d.velocity}</p>
                <p className="fact-value">{formatNumber(facts.velocityPerDay, locale, 1)}</p>
                <p className="fact-sub">{d.unitsSoldSub(formatCount(facts.unitsSold, locale))}</p>
              </div>
              <div className="fact-tile" data-tone={daysUnknown ? undefined : "warning"}>
                <p className="fact-label">{d.cover}</p>
                <p className="fact-value" data-testid="decision-cover">
                  {daysUnknown ? t.coverUnknown : coverText(decision, messages, locale)}
                </p>
                <p className="fact-sub">{d.thresholdSub(formatDays(facts.thresholdDays, locale, messages.common.dayForms))}</p>
              </div>
              <div className="fact-tile">
                <p className="fact-label">{d.restock}</p>
                <p className="fact-value">{facts.replenishmentKnown ? d.restockKnown : d.restockUnknown}</p>
              </div>
            </div>
          </section>

          <section className="card" aria-labelledby="why-title" data-testid="decision-why">
            <h2 id="why-title">{d.whyMatters}</h2>
            <ul className="reason-list">
              {decision.reasonCodes.map((code) => (
                <li key={code}>{label(t.reasonCodes, code)}</li>
              ))}
            </ul>
            {decision.missingRequirements.length > 0 ? (
              <Callout tone="warning" title={t.missing}>
                <ul className="plain-list">
                  {decision.missingRequirements.map((code) => (
                    <li key={code}>{label(t.reasonCodes, code)}</li>
                  ))}
                </ul>
              </Callout>
            ) : null}
          </section>

          <section className="card recommendation-card" aria-labelledby="recommendation-title">
            <h2 id="recommendation-title">{d.recommendation}</h2>
            {decision.action ? (
              <>
                <p className="lead" data-testid="decision-action">{recommendationText(decision, t)}</p>
                <dl className="details">
                  <dt>{t.target}</dt>
                  <dd>
                    {decision.action.targetName ?? "—"} <code>{decision.action.targetExternalId}</code>
                  </dd>
                </dl>
                <p className="scope-note">{d.scope}</p>
                <p className="muted">{d.afterApproval}</p>
              </>
            ) : (
              <>
                <p className="lead" data-testid="decision-action">{t.noAction}</p>
                <p>{d.noActionText[decision.disposition]}</p>
              </>
            )}
            {decision.excludedAds.length > 0 ? (
              <div className="excluded">
                <h3 className="subheading">{t.excludedAds}</h3>
                <ul className="plain-list" data-testid="excluded-ads">
                  {decision.excludedAds.map((ad) => (
                    <li key={ad.adId}>
                      {t.excludedAd(ad.name ?? ad.externalId ?? ad.adId)} <code>{ad.externalId}</code>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </section>

          {decision.execution ? (
            <ExecutionTimeline execution={decision.execution} timezone={timezone} locale={locale}
              t={messages.execution} />
          ) : null}

          <section className="card" aria-labelledby="evidence-title" data-testid="decision-evidence">
            <h2 id="evidence-title">{t.evidence}</h2>
            {evidence.data ? (
              <>
                <p className="muted">{t.dataUsed(at(evidence.data.snapshot.asOf))}</p>
                <ul className="evidence-list">
                  {evidence.data.items.map((item) => {
                    const value = evidenceValue(item, messages, locale);
                    return (
                      <li key={item.id} className="evidence-row">
                        <div className="evidence-main">
                          <p className="evidence-type">
                            {label(t.evidenceTypes, item.type)}
                            {item.citedByProposal ? (
                              <StatusBadge tone="info" icon={false}>{t.cited}</StatusBadge>
                            ) : null}
                          </p>
                          <p className={value ? "evidence-value" : "evidence-value muted"}>{value ?? d.evidenceUnreadable}</p>
                          <p className="evidence-time">{t.observed}: {at(item.observedAt)}</p>
                        </div>
                        <details className="evidence-technical">
                          <summary>{t.technical}</summary>
                          <pre className="json">{JSON.stringify(item.content, null, 2)}</pre>
                          <code>{item.contentHash}</code>
                        </details>
                      </li>
                    );
                  })}
                </ul>
              </>
            ) : (
              <Callout tone="danger" role="alert" title={messages.errorPage.title} />
            )}
          </section>

          {decision.policyEvaluation ? (
            <section className="card" aria-labelledby="checks-title" data-testid="decision-checks">
              <h2 id="checks-title">{d.checks}</h2>
              <p className="checks-summary">
                {d.checksSummary(passed, checks.length, label(t.policyOutcome, decision.policyEvaluation.outcome))}
              </p>
              {passed < checks.length ? (
                <Callout tone="danger" title={d.checksFailedTitle}>{d.checksFailedText}</Callout>
              ) : null}
              <table className="table checks-table" data-testid="policy-checks">
                <tbody>
                  {checks.map((check) => (
                    <tr key={check.name}>
                      <th scope="row">{label(t.checks, check.name)}</th>
                      <td>
                        <StatusBadge tone={CHECK_TONE[check.result] ?? "neutral"}>
                          {label(t.checkResult, check.result)}
                        </StatusBadge>
                      </td>
                      <td>{check.code ? label(t.policyCodes, check.code) : ""}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          ) : null}

          <details className="card technical-card">
            <summary>{d.technicalTitle}</summary>
            <h3 className="subheading">{d.originalSummary}</h3>
            <blockquote className="quote" lang="en">{decision.businessSummary}</blockquote>
            {decision.revisions.length > 1 ? (
              <>
                <h3 className="subheading">{t.revisions}</h3>
                <ol className="plain-list">
                  {decision.revisions.map((revision) => (
                    <li key={revision.proposalId}>
                      {revision.proposalId === decision.proposalId ? (
                        <strong>{t.revision(revision.revision)}</strong>
                      ) : (
                        <Link href={decisionPath(storeId, revision.proposalId)}>{t.revision(revision.revision)}</Link>
                      )}{" "}
                      · {t.proposalStatus[revision.status]} · {at(revision.createdAt)}
                    </li>
                  ))}
                </ol>
              </>
            ) : null}
            <h3 className="subheading">{d.ids}</h3>
            <dl className="details">
              <dt>proposalId</dt>
              <dd><code>{decision.proposalId}</code></dd>
              <dt>opportunityId</dt>
              <dd><code>{decision.opportunityId}</code></dd>
              <dt>proposer</dt>
              <dd><code>{decision.proposer.name}@{decision.proposer.version}</code></dd>
              {decision.policyEvaluation ? (
                <>
                  <dt>policy</dt>
                  <dd>
                    <code>
                      {decision.policyEvaluation.policyKey}@{decision.policyEvaluation.policyVersion} ·{" "}
                      {decision.policyEvaluation.mode}
                    </code>
                  </dd>
                </>
              ) : null}
              {evidence.data ? (
                <>
                  <dt>snapshot</dt>
                  <dd><code>{evidence.data.snapshot.id}</code></dd>
                </>
              ) : null}
              <dt>revision</dt>
              <dd><code>{decision.revision}</code></dd>
              <dt>requestId</dt>
              <dd><code>{decision.meta.requestId ?? "—"}</code></dd>
            </dl>
          </details>
        </div>

        <aside className="decision-rail" id="decision-panel" aria-labelledby="panel-title">
          <section className="card decision-panel" data-testid="decision-panel">
            <h2 id="panel-title">{d.panel}</h2>
            <p className="panel-status">
              <StatusBadge tone={toneOf(PROPOSAL_STATUS_TONE, decision.status)}>{t.proposalStatus[decision.status]}</StatusBadge>
            </p>
            {decision.status === "PROPOSED" ? <p className="panel-deadline">{deadline}</p> : null}
            {pending ? (
              <div className="decision-actions">
                {blocked ? (
                  <Callout
                    id="approval-blocked"
                    tone={blocked === "MUTATIONS_STOPPED" || blocked === "PROPOSAL_EXPIRED" ? "danger" : "warning"}
                    role="note"
                    data-testid="approval-blocked"
                    title={blockedTitle(blocked, t, messages)}
                  >
                    {blockedText(blocked, t, messages)}
                  </Callout>
                ) : null}
                {decision.action && (canApprove || canReject) ? (
                  <DecisionCommands
                    storeId={storeId}
                    proposalId={proposalId}
                    revision={decision.revision}
                    etag={etag}
                    adLabel={`${decision.action.targetName ?? ""} (${decision.action.targetExternalId})`.trim()}
                    canApprove={canApprove}
                    canReject={canReject}
                    bar={canApprove && !blocked ? { action: recommendationText(decision, t), deadline: shortDeadline } : undefined}
                  />
                ) : null}
                {!canApprove ? (
                  <button type="button" className="button primary" disabled
                    aria-describedby={blocked ? "approval-blocked" : "approval-read-only"}>
                    {messages.approval.approve}
                  </button>
                ) : null}
                {!blocked && !canApprove ? (
                  <p className="muted" id="approval-read-only">{d.readOnly}</p>
                ) : null}
              </div>
            ) : null}
            {decision.execution ? (
              <p className="panel-outcome">
                {decision.execution.status
                  ? label(messages.execution.statuses, decision.execution.status)
                  : decision.execution.decision === "APPROVED"
                    ? messages.execution.approvedAt(at(decision.execution.decidedAt))
                    : messages.execution.rejectedAt(at(decision.execution.decidedAt))}{" "}
                <a href="#execution-title">{messages.execution.title}</a>
              </p>
            ) : null}
          </section>
        </aside>
      </div>

      {pending && blocked ? (
        <div className="action-bar" data-testid="action-bar">
          <div className="action-bar-text">
            <strong>{blockedTitle(blocked, t, messages)}</strong>
            <span>{shortDeadline}</span>
          </div>
          <a href="#decision-panel" className="button secondary">
            <Icon icon={ArrowDown} /> {d.seeWhy}
          </a>
        </div>
      ) : null}
    </>
  );
}

function blockedTitle(code: string, t: Messages["decisions"], messages: Messages): string {
  if (code === "SHADOW_MODE") {
    return t.shadowModeTitle;
  }
  if (code === "PROPOSAL_EXPIRED") {
    return t.proposalStatus.EXPIRED;
  }
  if (code === "MUTATIONS_STOPPED") {
    return messages.mutationStop.stopped;
  }
  return t.blockedTitle;
}

function blockedText(code: string, t: Messages["decisions"], messages: Messages): string {
  if (code === "SHADOW_MODE") {
    return t.shadowMode;
  }
  if (code === "PROPOSAL_EXPIRED") {
    return t.expiredNote;
  }
  return messages.approval.errors[code] ?? label(t.policyCodes, code);
}
