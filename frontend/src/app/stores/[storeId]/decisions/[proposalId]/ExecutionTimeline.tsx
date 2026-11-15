import type { components } from "@/api/schema";
import { formatInstant } from "@/i18n/format";
import type { Locale } from "@/i18n/locale";
import type { Messages } from "@/i18n/messages";

import { label } from "../labels";

type Execution = NonNullable<components["schemas"]["DecisionPresentation"]["execution"]>;

const CONFIRMED = new Set(["VERIFIED", "NOOP"]);
const PROBLEM = new Set(["UNKNOWN", "FAILED", "CONFLICT"]);

/**
 * ExecutionTimeline (04 §5, UX-06): the decision, the fresh authorization, the ledger state and the outcome. Only a
 * read of the ad confirms an effect; an accepted command or UNKNOWN is never shown as done, and there is no resend.
 */
export function ExecutionTimeline({
  execution,
  timezone,
  locale,
  t,
}: {
  execution: Execution;
  timezone: string;
  locale: Locale;
  t: Messages["execution"];
}) {
  const at = (value: string | undefined | null) => formatInstant(value, timezone, locale);
  const status = execution.status;
  const tone = status && CONFIRMED.has(status) ? "done" : status && PROBLEM.has(status) ? "problem" : "pending";
  return (
    <section className="card" aria-labelledby="execution-title" data-testid="execution-timeline">
      <h2 id="execution-title">{t.title}</h2>
      <ol className="timeline">
        <li className="timeline-step done">
          {execution.decision === "APPROVED" ? t.approvedAt(at(execution.decidedAt)) : t.rejectedAt(at(execution.decidedAt))}
          {execution.reason ? (
            <p className="muted">
              {t.reason}: {execution.reason}
            </p>
          ) : null}
          {execution.decision === "APPROVED" && !execution.executionId ? (
            <p className="muted">{t.approvalValidUntil(at(execution.approvalExpiresAt))}</p>
          ) : null}
        </li>
        {execution.decision === "APPROVED" ? (
          <li className={`timeline-step ${execution.authorizedAt ? "done" : "pending"}`}>
            {execution.authorizedAt ? t.authorizedAt(at(execution.authorizedAt)) : t.notAuthorized}
          </li>
        ) : null}
        {status ? (
          <li className={`timeline-step ${tone}`} data-testid="execution-status" data-status={status}>
            <strong>{t.statusLabel}:</strong> {label(t.statuses, status)}
            {execution.code ? <p className="muted">{label(t.codes, execution.code)}</p> : null}
            <p className="muted">
              {t.attempts(execution.attempts)} · {t.updated(at(execution.updatedAt))}
            </p>
          </li>
        ) : null}
        {execution.outcome ? (
          <li className="timeline-step done" data-testid="execution-outcome">
            <strong>{t.outcomeTitle}:</strong> {label(t.authorship, execution.outcome.authorship)}
            {execution.outcome.observations.length > 0 ? (
              <>
                <h3 className="subheading">{t.observationsTitle}</h3>
                <p className="muted">{t.observationsIntro}</p>
                <table className="table">
                  <tbody>
                    {execution.outcome.observations.map((observation) => (
                      <tr key={observation.checkpoint}>
                        <th scope="row">{label(t.checkpoints, observation.checkpoint)}</th>
                        <td>
                          {label(t.observationStatus, observation.status)}
                          {observation.status === "PENDING" ? (
                            <span className="muted"> · {t.dueAt(at(observation.dueAt))}</span>
                          ) : null}
                        </td>
                        <td>
                          {Object.entries(observation.values).map(([key, value]) => (
                            <div key={key}>
                              {label(t.values, key)}: {value ?? t.notAvailable}
                            </div>
                          ))}
                          {observation.limitations.map((code) => (
                            <div key={code} className="muted">
                              {label(t.limitations, code)}
                            </div>
                          ))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            ) : null}
          </li>
        ) : null}
      </ol>
      {execution.executionId ? (
        <details>
          <summary>{t.technical}</summary>
          <code>{execution.executionId}</code>
        </details>
      ) : null}
    </section>
  );
}
