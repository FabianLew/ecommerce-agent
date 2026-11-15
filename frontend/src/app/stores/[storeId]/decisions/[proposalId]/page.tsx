import Link from "next/link";
import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { formatInstant, formatNumber } from "@/i18n/format";
import type { Messages } from "@/i18n/messages";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath, storePath } from "@/lib/store-path";

import { coverText, label, problemTitle, recommendationText } from "../labels";
import { DecisionCommands } from "./DecisionCommands";
import { ExecutionTimeline } from "./ExecutionTimeline";

export const dynamic = "force-dynamic";

/**
 * Decision Detail (04 §5) in three levels: summary with the proposed action, the "Why" panel with reasons and
 * policy checks, and the evidence the decision used. Approval and rejection open a confirmation dialog; in shadow
 * mode, or while changes are stopped, the approval button is disabled with the reason.
 */
export default async function DecisionDetailPage({
  params,
}: {
  params: Promise<{ storeId: string; proposalId: string }>;
}) {
  const [{ storeId, proposalId }, locale, messages] = await Promise.all([params, getLocale(), getMessages()]);
  const t = messages.decisions;
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

  return (
    <>
      <p>
        <Link href={storePath(storeId, "decisions")}>{t.backToList}</Link>
      </p>
      <PageHeading>{problemTitle(decision, t)}</PageHeading>
      <p className="badges">
        <span className="badge" data-testid="decision-status">{t.proposalStatus[decision.status]}</span>
        <span className="badge">{t.disposition[decision.disposition]}</span>
        <span className="muted">{t.revision(decision.revision)}</span>
      </p>

      <section className="card" aria-labelledby="summary-title" data-testid="decision-summary">
        <h2 id="summary-title">{t.proposedAction}</h2>
        <p className="lead" data-testid="decision-action">
          {decision.action ? recommendationText(decision, t) : t.noAction}
        </p>
        <dl className="details">
          {decision.action ? (
            <>
              <dt>{t.target}</dt>
              <dd>
                {decision.action.targetName ?? "—"} <code>{decision.action.targetExternalId}</code>
              </dd>
            </>
          ) : null}
          <dt>{t.cover}</dt>
          <dd data-testid="decision-cover">{coverText(decision, t, locale)}</dd>
          <dt>{t.facts.threshold}</dt>
          <dd>{t.coverDays(formatNumber(facts.thresholdDays, locale))}</dd>
          <dt>{t.facts.available}</dt>
          <dd>{facts.availableToSell ?? "—"}</dd>
          <dt>{t.facts.unitsSold}</dt>
          <dd>{formatNumber(facts.unitsSold, locale, 0)}</dd>
          <dt>{t.facts.velocity}</dt>
          <dd>{formatNumber(facts.velocityPerDay, locale, 2)}</dd>
          <dt>{t.facts.stockConfirmed}</dt>
          <dd>{at(facts.stockConfirmedAt)}</dd>
          <dt>{t.facts.replenishment}</dt>
          <dd>{facts.replenishmentKnown ? t.facts.replenishmentKnown : t.facts.replenishmentUnknown}</dd>
          <dt>{t.created}</dt>
          <dd>{at(decision.createdAt)}</dd>
          <dt>{t.expires}</dt>
          <dd>{at(decision.expiresAt)}</dd>
        </dl>
        {decision.action && decision.status === "PROPOSED" ? (
          <div className="decision-actions">
            {blocked ? (
              <p id="approval-blocked" className="form-message warning" role="note" data-testid="approval-blocked">
                <strong>{blockedTitle(blocked, t, messages)}:</strong> {blockedText(blocked, t, messages)}
              </p>
            ) : null}
            {decision.allowedActions.length > 0 ? (
              <DecisionCommands
                storeId={storeId}
                proposalId={proposalId}
                revision={decision.revision}
                etag={etag}
                adLabel={`${decision.action.targetName ?? ""} (${decision.action.targetExternalId})`.trim()}
                canApprove={decision.allowedActions.includes("APPROVE")}
                canReject={decision.allowedActions.includes("REJECT")}
              />
            ) : (
              <button type="button" className="button primary" disabled aria-describedby="approval-blocked">
                {t.approve}
              </button>
            )}
          </div>
        ) : null}
      </section>

      {decision.execution ? (
        <ExecutionTimeline execution={decision.execution} timezone={timezone} locale={locale}
          t={messages.execution} />
      ) : null}

      <section className="card" aria-labelledby="why-title" data-testid="decision-why">
        <h2 id="why-title">{t.why}</h2>
        <h3 className="subheading">{t.reasons}</h3>
        <ul className="plain-list">
          {decision.reasonCodes.map((code) => (
            <li key={code}>{label(t.reasonCodes, code)}</li>
          ))}
        </ul>
        {decision.missingRequirements.length > 0 ? (
          <>
            <h3 className="subheading">{t.missing}</h3>
            <ul className="plain-list">
              {decision.missingRequirements.map((code) => (
                <li key={code}>
                  <code>{code}</code>
                </li>
              ))}
            </ul>
          </>
        ) : null}
        {decision.excludedAds.length > 0 ? (
          <>
            <h3 className="subheading">{t.excludedAds}</h3>
            <ul className="plain-list" data-testid="excluded-ads">
              {decision.excludedAds.map((ad) => (
                <li key={ad.adId}>
                  {t.excludedAd(ad.name ?? ad.externalId ?? ad.adId)} <code>{ad.externalId}</code>
                </li>
              ))}
            </ul>
          </>
        ) : null}
        <h3 className="subheading">{t.proposerSummary}</h3>
        <blockquote className="quote" lang="en">{decision.businessSummary}</blockquote>
        {decision.policyEvaluation ? (
          <>
            <h3 className="subheading">
              {t.policyChecks}: {label(t.policyOutcome, decision.policyEvaluation.outcome)}
            </h3>
            <table className="table" data-testid="policy-checks">
              <tbody>
                {decision.policyEvaluation.checks.map((check) => (
                  <tr key={check.name}>
                    <th scope="row">{label(t.checks, check.name)}</th>
                    <td>{label(t.checkResult, check.result)}</td>
                    <td>{check.code ? label(t.policyCodes, check.code) : ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        ) : null}
      </section>

      <section className="card" aria-labelledby="evidence-title" data-testid="decision-evidence">
        <h2 id="evidence-title">{t.evidence}</h2>
        {evidence.data ? (
          <>
            <p className="muted">{t.dataUsed(at(evidence.data.snapshot.asOf))}</p>
            <table className="table">
              <tbody>
                {evidence.data.items.map((item) => (
                  <tr key={item.id}>
                    <th scope="row">
                      {label(t.evidenceTypes, item.type)}
                      {item.citedByProposal ? <span className="badge">{t.cited}</span> : null}
                    </th>
                    <td>
                      {t.observed}: {at(item.observedAt)}
                    </td>
                    <td>
                      <details>
                        <summary>{t.technical}</summary>
                        <pre className="json">{JSON.stringify(item.content, null, 2)}</pre>
                        <code>{item.contentHash}</code>
                      </details>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        ) : (
          <p className="form-message error" role="alert">{messages.errorPage.title}</p>
        )}
      </section>

      {decision.revisions.length > 1 ? (
        <section className="card" aria-labelledby="revisions-title">
          <h2 id="revisions-title">{t.revisions}</h2>
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
        </section>
      ) : null}

      <details className="card">
        <summary>{t.technical}</summary>
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
          <dt>requestId</dt>
          <dd><code>{decision.meta.requestId ?? "—"}</code></dd>
        </dl>
      </details>
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
  return messages.mutationStop.stopped;
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
