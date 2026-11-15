"use client";

import { useActionState } from "react";

import type { components } from "@/api/schema";
import { useMessages } from "@/i18n/LocaleProvider";

import { approveProfile, type ProfileFormState, saveProfileDraft } from "./actions";

type Profile = components["schemas"]["ProfileResponse"];

const INITIAL: ProfileFormState = { status: "idle" };
const GOALS = ["PROFITABILITY", "GROWTH", "BALANCED"] as const;
const RISKS = ["LOW", "MEDIUM", "HIGH"] as const;
const PRIORITIES = ["INVENTORY_CONTINUITY", "CONTRIBUTION_MARGIN", "CASH_PRESERVATION", "CUSTOMER_ACQUISITION"] as const;

function amount(minor: number | undefined): string {
  return minor === undefined ? "" : `${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, "0")}`;
}

/** Draft editor: starts from the draft, or from the approved version for a new draft. */
export function ProfileForm({
  storeId,
  draft,
  approved,
  currency,
}: {
  storeId: string;
  draft?: Profile;
  approved?: Profile;
  currency: string;
}) {
  const t = useMessages().profile;
  const base = draft ?? approved;
  const revision = draft?.revision ?? 0;
  const [state, save, saving] = useActionState(saveProfileDraft.bind(null, storeId, revision), INITIAL);
  const [approval, approve, approving] = useActionState(approveProfile.bind(null, storeId, revision), INITIAL);
  const message = approval.message ? approval : state;

  return (
    <>
      <form action={save} className="form" noValidate data-testid="profile-form">
        <div className="field">
          <label htmlFor="goal">{t.goal}</label>
          <p className="field-hint" id="goal-hint">{t.goalHint}</p>
          <select id="goal" name="goal" defaultValue={base?.goal ?? ""} aria-describedby="goal-hint">
            <option value="">{t.notSet}</option>
            {GOALS.map((goal) => (
              <option key={goal} value={goal}>{t.goals[goal]}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="planningHorizonDays">{t.horizon}</label>
          <input
            id="planningHorizonDays"
            name="planningHorizonDays"
            type="number"
            min={7}
            max={365}
            defaultValue={base?.planningHorizonDays ?? ""}
          />
        </div>
        <div className="field">
          <label htmlFor="riskPreference">{t.risk}</label>
          <select id="riskPreference" name="riskPreference" defaultValue={base?.riskPreference ?? ""}>
            <option value="">{t.notSet}</option>
            {RISKS.map((risk) => (
              <option key={risk} value={risk}>{t.risks[risk]}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="taxBasis">{t.taxBasis}</label>
          <select id="taxBasis" name="taxBasis" defaultValue={base?.taxBasis ?? "EX_TAX"}>
            <option value="EX_TAX">{t.taxBases.EX_TAX}</option>
            <option value="INC_TAX">{t.taxBases.INC_TAX}</option>
          </select>
        </div>
        <fieldset className="field">
          <legend>{t.priorities}</legend>
          {PRIORITIES.map((priority) => (
            <label key={priority} className="choice">
              <input
                type="checkbox"
                name="priorities"
                value={priority}
                defaultChecked={base?.priorities.includes(priority)}
              />
              {t.priorityNames[priority]}
            </label>
          ))}
        </fieldset>
        <div className="field">
          <label htmlFor="plannedAdSpend">{t.plannedSpend(currency)}</label>
          <input
            id="plannedAdSpend"
            name="plannedAdSpend"
            inputMode="decimal"
            defaultValue={amount(base?.plannedAdSpend?.amountMinor)}
          />
        </div>
        <div className="field">
          <label htmlFor="plannedAdSpendPeriod">{t.plannedSpendPeriod}</label>
          <input
            id="plannedAdSpendPeriod"
            name="plannedAdSpendPeriod"
            type="month"
            defaultValue={base?.plannedAdSpend?.period ?? ""}
          />
        </div>
        <div className="field">
          <label htmlFor="note">{t.note}</label>
          <textarea id="note" name="note" maxLength={2000} rows={3} defaultValue={base?.note ?? ""} />
        </div>
        {message.message ? (
          <p className={message.status === "error" ? "form-message error" : "form-message"} role="status">
            {message.message}
          </p>
        ) : null}
        <div className="form-actions">
          <button type="submit" className="button secondary" disabled={saving}>
            {saving ? t.saving : t.saveDraft}
          </button>
        </div>
      </form>
      {draft ? (
        <form action={approve} className="form-actions inline-form">
          <button type="submit" className="button primary" disabled={approving} data-testid="profile-approve">
            {approving ? t.saving : t.approve}
          </button>
        </form>
      ) : null}
    </>
  );
}
