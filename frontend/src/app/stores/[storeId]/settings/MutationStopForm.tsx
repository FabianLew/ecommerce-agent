"use client";

import { useActionState } from "react";

import { useMessages } from "@/i18n/LocaleProvider";

import { changeMutationStop, type StopFormState } from "./stop-actions";

const INITIAL: StopFormState = { status: "idle" };

/** Stop (with a reason) or resume; the server checks the permission and the version again. */
export function MutationStopForm({
  storeId,
  version,
  stopped,
}: {
  storeId: string;
  version: number;
  stopped: boolean;
}) {
  const t = useMessages().mutationStop;
  const [state, submit, pending] = useActionState(
    changeMutationStop.bind(null, storeId, version, !stopped),
    INITIAL,
  );
  return (
    <form action={submit} className="form" noValidate data-testid="mutation-stop-form">
      {stopped ? null : (
        <div className="field">
          <label htmlFor="stop-reason">{t.reason}</label>
          <p className="field-hint" id="stop-reason-hint">{t.reasonHint}</p>
          <textarea id="stop-reason" name="reason" maxLength={500} rows={2} aria-describedby="stop-reason-hint" />
        </div>
      )}
      <div className="form-actions">
        <button type="submit" className={stopped ? "button primary" : "button danger"} disabled={pending}>
          {stopped ? (pending ? t.resuming : t.resume) : pending ? t.stopping : t.stop}
        </button>
      </div>
      {state.message ? (
        <p className={`form-message${state.status === "error" ? " error" : ""}`} role="status">
          {state.message}
        </p>
      ) : null}
    </form>
  );
}
