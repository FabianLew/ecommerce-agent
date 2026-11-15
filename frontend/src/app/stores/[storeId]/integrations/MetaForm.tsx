"use client";

import { useActionState } from "react";

import { useMessages } from "@/i18n/LocaleProvider";

import { connectMeta, type MetaFormState } from "./meta-actions";

const INITIAL: MetaFormState = { status: "idle" };

/** Ad account and token form. The token is a password input without autofill and is never echoed back. */
export function MetaForm({ storeId, connectionId }: { storeId: string; connectionId?: string }) {
  const [state, action, pending] = useActionState(connectMeta.bind(null, storeId), INITIAL);
  const errors = state.fieldErrors ?? {};
  const t = useMessages().meta;

  return (
    <form action={action} className="form" noValidate>
      {connectionId ? <input type="hidden" name="connectionId" value={connectionId} /> : null}
      {connectionId ? null : (
        <div className="field">
          <label htmlFor="metaAccountId">{t.accountId}</label>
          <p id="metaAccountId-hint" className="field-hint">{t.accountIdHint}</p>
          <input
            id="metaAccountId"
            name="accountId"
            required
            inputMode="numeric"
            autoComplete="off"
            placeholder="act_"
            defaultValue={state.accountId}
            aria-invalid={errors.accountId ? true : undefined}
            aria-describedby={errors.accountId ? "metaAccountId-hint metaAccountId-error" : "metaAccountId-hint"}
          />
          {errors.accountId ? <p id="metaAccountId-error" className="field-error">{errors.accountId}</p> : null}
        </div>
      )}
      <div className="field">
        <label htmlFor="metaToken">{t.token}</label>
        <p id="metaToken-hint" className="field-hint">{t.tokenHint}</p>
        <input
          id="metaToken"
          name="token"
          type="password"
          required
          autoComplete="off"
          spellCheck={false}
          aria-invalid={errors.token ? true : undefined}
          aria-describedby={errors.token ? "metaToken-hint metaToken-error" : "metaToken-hint"}
        />
        {errors.token ? <p id="metaToken-error" className="field-error">{errors.token}</p> : null}
      </div>
      {state.message ? (
        <p className={state.status === "error" ? "form-message error" : "form-message"} role="status">
          {state.message}
        </p>
      ) : null}
      <div className="form-actions">
        <button type="submit" className="button primary" disabled={pending}>
          {pending ? t.checking : connectionId ? t.saveToken : t.connect}
        </button>
      </div>
    </form>
  );
}
