"use client";

import { useActionState, useState } from "react";

import type { components } from "@/api/schema";
import { useMessages } from "@/i18n/LocaleProvider";

import { type MappingFormState, saveMapping } from "./actions";

type AdItem = components["schemas"]["AdResponse"];
type VariantItem = components["schemas"]["VariantResponse"];

const INITIAL: MappingFormState = { status: "idle" };

/** One ad's mapping form: scope, variants and the explicit confirmation for the current creative. */
export function MappingForm({ storeId, ad, variants }: { storeId: string; ad: AdItem; variants: VariantItem[] }) {
  const t = useMessages().adMappings;
  const version = ad.mapping?.version ?? 0;
  const [state, action, pending] = useActionState(saveMapping.bind(null, storeId, ad.id, version), INITIAL);
  const [scope, setScope] = useState<"SINGLE_VARIANT" | "MULTI_PRODUCT">(ad.mapping?.scope ?? "SINGLE_VARIANT");
  const selected = new Set(ad.mapping?.variants.map((variant) => variant.id) ?? []);
  const prefix = `mapping-${ad.id}`;

  return (
    <form action={action} className="form compact" noValidate data-testid="mapping-form">
      <fieldset className="field">
        <legend>{t.scopeLabel}</legend>
        {(["SINGLE_VARIANT", "MULTI_PRODUCT"] as const).map((value) => (
          <label key={value} className="choice">
            <input
              type="radio"
              name="scope"
              value={value}
              checked={scope === value}
              onChange={() => setScope(value)}
            />
            {t.scope[value]}
          </label>
        ))}
      </fieldset>
      <div className="field">
        <label htmlFor={`${prefix}-variants`}>{t.variantsLabel}</label>
        <select
          id={`${prefix}-variants`}
          name="variantIds"
          multiple={scope === "MULTI_PRODUCT"}
          defaultValue={scope === "MULTI_PRODUCT" ? [...selected] : [...selected][0] ?? ""}
          key={scope}
          aria-invalid={state.variantError ? true : undefined}
          aria-describedby={state.variantError ? `${prefix}-error` : undefined}
        >
          {scope === "SINGLE_VARIANT" ? <option value="">—</option> : null}
          {variants.map((variant) => (
            <option key={variant.id} value={variant.id}>
              {variant.sku ? `${variant.sku} · ` : ""}
              {variant.productName} – {variant.name}
            </option>
          ))}
        </select>
        {state.variantError ? <p id={`${prefix}-error`} className="field-error">{state.variantError}</p> : null}
      </div>
      <label className="choice">
        <input type="checkbox" name="verified" defaultChecked={false} />
        {t.verifiedLabel}
      </label>
      {state.message ? (
        <p className={state.status === "error" ? "form-message error" : "form-message"} role="status">
          {state.message}
        </p>
      ) : null}
      <div className="form-actions">
        <button type="submit" className="button primary" disabled={pending}>
          {pending ? t.saving : t.save}
        </button>
      </div>
    </form>
  );
}
