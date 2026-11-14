"use client";

import { useActionState, useState } from "react";

import { saveWebhookSecret, type WebhookSecretState } from "./actions";
import { TOPIC_LABELS, type Integration } from "./labels";

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

  return (
    <div className="webhook-setup">
      <p className="muted">
        Webhooki przyspieszają aktualizację stanów i zamówień. Utwórz je ręcznie w WooCommerce → Ustawienia →
        Zaawansowane → Webhooki: po jednym dla każdego tematu, status „Aktywny”, wersja API „WP REST API
        Integration v3”, ten sam adres i ten sam sekret. Bez webhooków dane i tak odświeży okresowa synchronizacja.
      </p>
      <dl className="details">
        <dt>Adres dostawy</dt>
        <dd data-testid="webhook-url">
          {webhook.deliveryUrl ? <code>{webhook.deliveryUrl}</code> : "Adres publiczny API nie jest skonfigurowany."}
        </dd>
        <dt>Tematy</dt>
        <dd>
          <ul className="plain-list">
            {webhook.topics.map((topic) => (
              <li key={topic}>{TOPIC_LABELS[topic] ?? topic}</li>
            ))}
          </ul>
        </dd>
      </dl>
      {canManage ? (
        <form action={action} className="form" noValidate>
          <div className="field">
            <label htmlFor="webhookSecret">Sekret webhooków</label>
            <p id="webhookSecret-hint" className="field-hint">
              Wygeneruj sekret, wklej go w polu „Sekret” każdego webhooka w WooCommerce i zapisz tutaj. Nie pokażemy
              go ponownie.
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
                Wygeneruj sekret
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
              {webhook.secretConfigured ? "Zapisz nowy sekret" : "Zapisz sekret"}
            </button>
          </div>
        </form>
      ) : null}
    </div>
  );
}
