"use client";

import { useActionState } from "react";

import type { components } from "@/api/schema";
import { useMessages } from "@/i18n/LocaleProvider";

import { changeActionPermission, type PermissionFormState } from "./permission-actions";

type Level = components["schemas"]["ActionPermissionRequest"]["level"];

const INITIAL: PermissionFormState = { status: "idle" };
const ORDER: Level[] = ["OFF", "PROPOSE_ONLY", "APPROVAL_REQUIRED"];

/** Choose the store's level; only the levels this person may set are offered, and the server checks again. */
export function ActionPermissionForm({
  storeId,
  version,
  level,
  canRaise,
}: {
  storeId: string;
  version: number;
  level: Level;
  canRaise: boolean;
}) {
  const t = useMessages().actionPermission;
  const [state, submit, pending] = useActionState(
    changeActionPermission.bind(null, storeId, version, level),
    INITIAL,
  );
  const options = ORDER.filter((option) => canRaise || ORDER.indexOf(option) <= ORDER.indexOf(level));
  return (
    <form action={submit} className="form" noValidate data-testid="action-permission-form">
      <fieldset className="field">
        <legend>{t.level}</legend>
        {options.map((option) => (
          <label key={option} className="choice">
            <input type="radio" name="level" value={option} defaultChecked={option === level} />
            {t.levels[option]}
          </label>
        ))}
      </fieldset>
      <div className="field">
        <label htmlFor="permission-reason">{t.reason}</label>
        <p className="field-hint" id="permission-reason-hint">{t.reasonHint}</p>
        <textarea id="permission-reason" name="reason" maxLength={500} rows={2}
          aria-describedby="permission-reason-hint" />
      </div>
      <div className="form-actions">
        <button type="submit" className="button primary" disabled={pending}>
          {pending ? t.saving : t.save}
        </button>
      </div>
      {!canRaise ? <p className="muted">{t.raiseReadOnly}</p> : null}
      {state.message ? (
        <p className={`form-message${state.status === "error" ? " error" : ""}`} role="status">
          {state.message}
        </p>
      ) : null}
    </form>
  );
}
