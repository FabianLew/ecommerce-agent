"use server";

import { revalidatePath } from "next/cache";

import type { Messages } from "@/i18n/messages";
import { getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

export type FormState = {
  status: "idle" | "error" | "saved";
  message?: string;
  fieldErrors?: Partial<Record<"storeUrl" | "consumerKey" | "consumerSecret" | "credentials", string>>;
  /** Non-secret input kept after a failed submit (React resets the form); secrets are never echoed. */
  storeUrl?: string;
};

const FIELD_NAMES = ["storeUrl", "consumerKey", "consumerSecret", "credentials"] as const;

function fieldErrors(
  errors: { field: string; code: string }[] | undefined,
  t: Messages["integrations"],
): FormState["fieldErrors"] {
  const result: FormState["fieldErrors"] = {};
  for (const error of errors ?? []) {
    const field = FIELD_NAMES.find((name) => name === error.field);
    if (field) {
      result[field] = Object.hasOwn(t.fieldMessages, error.code) ? t.fieldMessages[error.code] : t.invalidValue;
    }
  }
  return result;
}

function keyFormat(field: "consumerKey" | "consumerSecret", value: string, t: Messages["integrations"]): string | undefined {
  const prefix = field === "consumerKey" ? "ck_" : "cs_";
  if (!value) {
    return t.fieldMessages.REQUIRED;
  }
  return value.startsWith(prefix) ? undefined : t.keyPrefix(prefix);
}

/**
 * Creates the WooCommerce connection if needed, stores the key (sent only server-to-server) and runs the
 * read-only check. Secrets are not kept in the returned state, the session or the URL (ADR-0007).
 */
export async function connectWooCommerce(storeId: string, _previous: FormState, form: FormData): Promise<FormState> {
  const path = storePath(storeId, "integrations");
  const consumerKey = String(form.get("consumerKey") ?? "").trim();
  const consumerSecret = String(form.get("consumerSecret") ?? "").trim();
  const storeUrl = String(form.get("storeUrl") ?? "").trim();
  const t = (await getMessages()).integrations;
  const errors = {
    consumerKey: keyFormat("consumerKey", consumerKey, t),
    consumerSecret: keyFormat("consumerSecret", consumerSecret, t),
  };
  if (errors.consumerKey || errors.consumerSecret) {
    return { status: "error", message: t.fixFields, fieldErrors: errors, storeUrl };
  }

  const api = await backend(path);
  let integrationId = String(form.get("integrationId") ?? "");
  if (!integrationId) {
    const created = await api.POST("/api/v1/stores/{storeId}/integration-connections", {
      params: { path: { storeId } },
      body: { provider: "WOOCOMMERCE", storeUrl },
    });
    redirectIfUnauthenticated(created.response.status, path);
    if (!created.data) {
      return { ...failure(created.response.status, created.error, t), storeUrl };
    }
    integrationId = created.data.id;
  }

  const saved = await api.POST("/api/v1/stores/{storeId}/integration-connections/{connectionId}/credentials", {
    params: { path: { storeId, connectionId: integrationId } },
    body: { consumerKey, consumerSecret },
  });
  redirectIfUnauthenticated(saved.response.status, path);
  if (!saved.data) {
    revalidatePath(path, "layout");
    return failure(saved.response.status, saved.error, t);
  }

  const checked = await api.POST("/api/v1/stores/{storeId}/integrations/{integrationId}/checks", {
    params: { path: { storeId, integrationId } },
  });
  redirectIfUnauthenticated(checked.response.status, path);
  revalidatePath(path, "layout");
  if (!checked.data) {
    return failure(checked.response.status, checked.error, t);
  }
  return checked.data.status === "CONNECTED"
    ? { status: "saved", message: t.connected }
    : { status: "error", message: t.keySavedCheckFailed };
}

export async function checkConnection(storeId: string, integrationId: string): Promise<void> {
  const path = storePath(storeId, "integrations");
  const api = await backend(path);
  const checked = await api.POST("/api/v1/stores/{storeId}/integrations/{integrationId}/checks", {
    params: { path: { storeId, integrationId } },
  });
  redirectIfUnauthenticated(checked.response.status, path);
  revalidatePath(path, "layout");
}

function failure(
  status: number,
  error: { code?: string; fieldErrors?: { field: string; code: string }[]; requestId?: string } | undefined,
  t: Messages["integrations"],
): FormState {
  if (status === 403) {
    return { status: "error", message: t.noPermission };
  }
  if (status === 409) {
    return { status: "error", message: t.alreadyConnected };
  }
  if (status === 422) {
    return { status: "error", message: t.fixFields, fieldErrors: fieldErrors(error?.fieldErrors, t) };
  }
  return {
    status: "error",
    message: t.saveFailed(error?.requestId ?? "-"),
  };
}

/** Starts the initial import (14 days). It runs in the background; the page polls until it finishes. */
export async function startSync(storeId: string, integrationId: string): Promise<void> {
  const path = storePath(storeId, "integrations");
  const api = await backend(path);
  const started = await api.POST("/api/v1/stores/{storeId}/integrations/{integrationId}/sync-jobs", {
    params: { path: { storeId, integrationId } },
    body: { scope: "INITIAL_14_DAYS" },
  });
  redirectIfUnauthenticated(started.response.status, path);
  revalidatePath(path, "layout");
}

export type WebhookSecretState = { status: "idle" | "error" | "saved"; message?: string };

/**
 * Stores the webhook secret that the user also entered in WooCommerce (ADR-0009). The value was generated in
 * the browser; it is sent only server-to-server and never echoed back.
 */
export async function saveWebhookSecret(
  storeId: string,
  integrationId: string,
  _previous: WebhookSecretState,
  form: FormData,
): Promise<WebhookSecretState> {
  const path = storePath(storeId, "integrations");
  const secret = String(form.get("webhookSecret") ?? "").trim();
  const t = (await getMessages()).integrations;
  if (secret.length < 16) {
    return { status: "error", message: t.secretTooShort };
  }
  const api = await backend(path);
  const saved = await api.POST("/api/v1/stores/{storeId}/integration-connections/{connectionId}/webhook-secret", {
    params: { path: { storeId, connectionId: integrationId } },
    body: { secret },
  });
  redirectIfUnauthenticated(saved.response.status, path);
  revalidatePath(path, "layout");
  if (saved.data) {
    return { status: "saved", message: t.secretSaved };
  }
  if (saved.response.status === 403) {
    return { status: "error", message: t.noPermission };
  }
  if (saved.response.status === 422) {
    return { status: "error", message: t.secretInvalid };
  }
  return {
    status: "error",
    message: t.secretSaveFailed(saved.error?.requestId ?? "-"),
  };
}
