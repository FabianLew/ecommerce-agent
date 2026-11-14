"use client";

import { useActionState, useState } from "react";

import { useMessages } from "@/i18n/LocaleProvider";

import { saveWebhookSecret, type WebhookSecretState } from "./actions";
import type { Integration } from "./labels";

const INITIAL: WebhookSecretState = { status: "idle" };

/** 32 random bytes as base64url, generated in the browser so the backend never has to return it (ADR-0009). */
function generateSecret(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Manual webhook setup in WooCommerce: what to enter there, and the shared secret form. */
export function WebhookSetup({
  storeId,
  webhook,
  integrationId,
  canManage,
}: {
  storeId: string;
  webhook: Integration["webhook"];
  integrationId: string;
  canManage: boolean;
}) {
  const [state, action, pending] = useActionState(saveWebhookSecret.bind(null, storeId, integrationId), INITIAL);
  const [secret, setSecret] = useState("");
  const { integrations } = useMessages();
  const t = integrations.webhookSetup;

  return (
    <div className="webhook-setup">
      <p className="muted">{t.intro}</p>
      <dl className="details">
        <dt>{t.deliveryUrl}</dt>
        <dd data-testid="webhook-url">
          {webhook.deliveryUrl ? <code>{webhook.deliveryUrl}</code> : t.deliveryUrlMissing}
        </dd>
        <dt>{t.topics}</dt>
        <dd>
          <ul className="plain-list">
            {webhook.topics.map((topic) => (
              <li key={topic}>{integrations.topics[topic] ?? topic}</li>
            ))}
          </ul>
        </dd>
      </dl>
      {canManage ? (
        <form action={action} className="form" noValidate>
          <div className="field">
            <label htmlFor="webhookSecret">{t.secret}</label>
            <p id="webhookSecret-hint" className="field-hint">
              {t.secretHint}
            </p>
            <div className="inline-form">
              <input
                id="webhookSecret"
                name="webhookSecret"
                type="text"
                autoComplete="off"
                spellCheck={false}
                aria-describedby="webhookSecret-hint"
                value={secret}
                onChange={(event) => setSecret(event.target.value)}
              />
              <button type="button" className="button secondary" onClick={() => setSecret(generateSecret())}>
                {t.generate}
              </button>
            </div>
          </div>
          {state.message ? (
            <p className={state.status === "error" ? "form-message error" : "form-message"} role="status">
              {state.message}
            </p>
          ) : null}
          <div className="form-actions">
            <button type="submit" className="button primary" disabled={pending || !secret}>
              {webhook.secretConfigured ? t.saveNew : t.save}
            </button>
          </div>
        </form>
      ) : null}
    </div>
  );
}
