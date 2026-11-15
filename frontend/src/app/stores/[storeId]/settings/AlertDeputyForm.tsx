"use client";

import { useActionState } from "react";

import { useMessages } from "@/i18n/LocaleProvider";

import { changeAlertDeputy, type AlertFormState } from "./alert-actions";

const INITIAL: AlertFormState = { status: "idle" };

/** Choose the deputy among the members who may approve decisions; the server checks again. */
export function AlertDeputyForm({
  storeId,
  version,
  deputy,
  candidates,
}: {
  storeId: string;
  version: number;
  deputy: string | undefined;
  candidates: { userId: string; displayName?: string }[];
}) {
  const t = useMessages().alertDeputy;
  const [state, submit, pending] = useActionState(changeAlertDeputy.bind(null, storeId, version), INITIAL);
  return (
    <form action={submit} className="form" noValidate data-testid="alert-deputy-form">
      <div className="field">
        <label htmlFor="alert-deputy">{t.deputy}</label>
        <select id="alert-deputy" name="deputy" defaultValue={deputy ?? ""}>
          <option value="">{t.none}</option>
          {candidates.map((candidate) => (
            <option key={candidate.userId} value={candidate.userId}>
              {candidate.displayName ?? t.unnamed}
            </option>
          ))}
        </select>
      </div>
      <div className="form-actions">
        <button type="submit" className="button primary" disabled={pending}>
          {pending ? t.saving : t.save}
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
