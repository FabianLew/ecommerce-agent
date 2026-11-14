"use client";

import { useActionState } from "react";

import { connectWooCommerce, type FormState } from "./actions";

const INITIAL: FormState = { status: "idle" };

/** Key form. Secret fields are password inputs without autofill; values are never echoed back. */
export function WooCommerceForm({ storeId, integrationId }: { storeId: string; integrationId?: string }) {
  const [state, action, pending] = useActionState(connectWooCommerce.bind(null, storeId), INITIAL);
  const errors = state.fieldErrors ?? {};

  return (
    <form action={action} className="form" noValidate>
      {integrationId ? <input type="hidden" name="integrationId" value={integrationId} /> : null}
      {integrationId ? null : (
        <Field
          id="storeUrl"
          label="Adres sklepu"
          hint="Adres strony głównej sklepu, np. https://sklep.pl"
          error={errors.storeUrl}
          input={{ type: "url", inputMode: "url", autoComplete: "url", placeholder: "https://", defaultValue: state.storeUrl }}
        />
      )}
      <Field
        id="consumerKey"
        label="Consumer key"
        hint="WooCommerce → Ustawienia → Zaawansowane → REST API → Dodaj klucz, uprawnienie Odczyt."
        error={errors.consumerKey}
        input={{ type: "password", autoComplete: "off", spellCheck: false }}
      />
      <Field
        id="consumerSecret"
        label="Consumer secret"
        error={errors.consumerSecret ?? errors.credentials}
        input={{ type: "password", autoComplete: "off", spellCheck: false }}
      />
      {state.message ? (
        <p className={state.status === "error" ? "form-message error" : "form-message"} role="status">
          {state.message}
        </p>
      ) : null}
      <div className="form-actions">
        <button type="submit" className="button primary" disabled={pending}>
          {pending ? "Sprawdzanie…" : integrationId ? "Zapisz nowy klucz i sprawdź" : "Połącz i sprawdź"}
        </button>
      </div>
    </form>
  );
}

function Field({
  id,
  label,
  hint,
  error,
  input,
}: {
  id: "storeUrl" | "consumerKey" | "consumerSecret";
  label: string;
  hint?: string;
  error?: string;
  input: React.InputHTMLAttributes<HTMLInputElement>;
}) {
  const describedBy = [hint ? `${id}-hint` : null, error ? `${id}-error` : null].filter(Boolean).join(" ");
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {hint ? (
        <p id={`${id}-hint`} className="field-hint">
          {hint}
        </p>
      ) : null}
      <input
        id={id}
        name={id}
        required
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        {...input}
      />
      {error ? (
        <p id={`${id}-error`} className="field-error">
          {error}
        </p>
      ) : null}
    </div>
  );
}
