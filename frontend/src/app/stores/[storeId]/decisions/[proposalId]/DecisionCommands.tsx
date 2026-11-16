"use client";

import { useActionState, useRef, useState } from "react";

import { useMessages } from "@/i18n/LocaleProvider";

import { decide, type DecisionFormState } from "./actions";

const INITIAL: DecisionFormState = { status: "idle" };

/**
 * Approve and reject with a confirmation dialog (04 §5, UX-05). Each dialog carries one idempotency key; the
 * button is disabled while the command is pending, and the result is never shown as a paused ad. The optional
 * mobile action bar opens the same approve dialog, so there is one path to an approval.
 */
export function DecisionCommands({
  storeId,
  proposalId,
  revision,
  etag,
  adLabel,
  canApprove,
  canReject,
  bar,
}: {
  storeId: string;
  proposalId: string;
  revision: number;
  etag: string;
  adLabel: string;
  canApprove: boolean;
  canReject: boolean;
  /** Below 900 px: a compact sticky bar with the action, the deadline and the same approve dialog (plan 4.C). */
  bar?: { action: string; deadline: string };
}) {
  const t = useMessages().approval;
  const approveDialog = useRef<HTMLDialogElement>(null);
  const rejectDialog = useRef<HTMLDialogElement>(null);
  const [approveKey] = useState(() => crypto.randomUUID());
  const [rejectKey] = useState(() => crypto.randomUUID());
  const [approved, approve, approving] = useActionState(
    decide.bind(null, storeId, proposalId, revision, etag, "approve"),
    INITIAL,
  );
  const [rejected, reject, rejecting] = useActionState(
    decide.bind(null, storeId, proposalId, revision, etag, "reject"),
    INITIAL,
  );
  const result = approved.message ? approved : rejected;

  return (
    <>
      <div className="form-actions">
        {canApprove ? (
          <button
            type="button"
            className="button primary"
            onClick={() => approveDialog.current?.showModal()}
            data-testid="approve-open"
          >
            {t.approve}
          </button>
        ) : null}
        {canReject ? (
          <button type="button" className="button secondary" onClick={() => rejectDialog.current?.showModal()}>
            {t.reject}
          </button>
        ) : null}
      </div>
      {result.message ? (
        <p
          className={`form-message${result.status === "error" ? " error" : ""}`}
          role="status"
          data-testid="decision-command-result"
        >
          {result.message}
        </p>
      ) : null}

      {bar && canApprove ? (
        <div className="action-bar" data-testid="action-bar">
          <div className="action-bar-text">
            <strong>{bar.action}</strong>
            <span>{bar.deadline}</span>
          </div>
          <button type="button" className="button primary" onClick={() => approveDialog.current?.showModal()}>
            {t.approve}
          </button>
        </div>
      ) : null}

      <dialog ref={approveDialog} className="dialog" aria-labelledby="approve-title">
        <form action={approve} onSubmit={() => approveDialog.current?.close()}>
          <h2 id="approve-title">{t.approveTitle}</h2>
          <p>{t.approveIntro(adLabel)}</p>
          <ul className="plain-list">
            {t.approveChecks.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
          <p className="muted">{t.approveAfter}</p>
          <input type="hidden" name="idempotencyKey" value={approveKey} />
          <div className="form-actions">
            <button type="submit" className="button primary" disabled={approving} data-testid="approve-confirm">
              {approving ? t.sending : t.confirmApprove}
            </button>
            <button type="button" className="button secondary" onClick={() => approveDialog.current?.close()}>
              {t.cancel}
            </button>
          </div>
        </form>
      </dialog>

      <dialog ref={rejectDialog} className="dialog" aria-labelledby="reject-title">
        <form action={reject} onSubmit={() => rejectDialog.current?.close()}>
          <h2 id="reject-title">{t.rejectTitle}</h2>
          <p>{t.rejectIntro}</p>
          <div className="field">
            <label htmlFor="reject-reason">{t.reasonLabel}</label>
            <textarea id="reject-reason" name="reason" maxLength={1000} rows={3} />
          </div>
          <input type="hidden" name="idempotencyKey" value={rejectKey} />
          <div className="form-actions">
            <button type="submit" className="button danger" disabled={rejecting}>
              {rejecting ? t.sending : t.confirmReject}
            </button>
            <button type="button" className="button secondary" onClick={() => rejectDialog.current?.close()}>
              {t.cancel}
            </button>
          </div>
        </form>
      </dialog>
    </>
  );
}
