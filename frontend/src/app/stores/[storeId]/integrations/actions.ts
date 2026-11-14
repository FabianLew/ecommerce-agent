"use server";

import { revalidatePath } from "next/cache";

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

function fieldErrors(errors: { field: string; code: string }[] | undefined): FormState["fieldErrors"] {
  const result: FormState["fieldErrors"] = {};
  for (const error of errors ?? []) {
    const field = FIELD_NAMES.find((name) => name === error.field);
    if (field) {
      result[field] = FIELD_MESSAGES[error.code] ?? "Nieprawidłowa wartość.";
    }
  }
  return result;
}

/** Polish texts for the backend's validation codes (ADR-0007). */
const FIELD_MESSAGES: Record<string, string> = {
  REQUIRED: "To pole jest wymagane.",
  INVALID_URL: "Podaj poprawny adres sklepu, np. https://sklep.pl.",
  INSECURE_SCHEME: "Adres sklepu musi zaczynać się od https://.",
  CREDENTIALS_IN_URL: "Adres sklepu nie może zawierać loginu ani hasła.",
  QUERY_NOT_ALLOWED: "Podaj adres sklepu bez ? i #.",
  KEY_FORMAT: "Wartość z WooCommerce zawiera tylko litery, cyfry i _, a klucz zaczyna się od ck_, sekret od cs_.",
  CREDENTIALS_MISSING: "Najpierw zapisz klucz API.",
};

function keyFormat(field: "consumerKey" | "consumerSecret", value: string): string | undefined {
  const prefix = field === "consumerKey" ? "ck_" : "cs_";
  if (!value) {
    return "To pole jest wymagane.";
  }
  return value.startsWith(prefix) ? undefined : `Wartość z WooCommerce zaczyna się od ${prefix}.`;
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
  const errors = {
    consumerKey: keyFormat("consumerKey", consumerKey),
    consumerSecret: keyFormat("consumerSecret", consumerSecret),
  };
  if (errors.consumerKey || errors.consumerSecret) {
    return { status: "error", message: "Popraw zaznaczone pola.", fieldErrors: errors, storeUrl };
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
      return { ...failure(created.response.status, created.error), storeUrl };
    }
    integrationId = created.data.id;
  }

  const saved = await api.POST("/api/v1/stores/{storeId}/integration-connections/{connectionId}/credentials", {
    params: { path: { storeId, connectionId: integrationId } },
    body: { consumerKey, consumerSecret },
  });
  redirectIfUnauthenticated(saved.response.status, path);
  if (!saved.data) {
    revalidatePath(path);
    return failure(saved.response.status, saved.error);
  }

  const checked = await api.POST("/api/v1/stores/{storeId}/integrations/{integrationId}/checks", {
    params: { path: { storeId, integrationId } },
  });
  redirectIfUnauthenticated(checked.response.status, path);
  revalidatePath(path);
  if (!checked.data) {
    return failure(checked.response.status, checked.error);
  }
  return checked.data.status === "CONNECTED"
    ? { status: "saved", message: "Połączono z WooCommerce." }
    : { status: "error", message: "Klucz zapisany, ale test połączenia się nie powiódł. Szczegóły poniżej." };
}

export async function checkConnection(storeId: string, integrationId: string): Promise<void> {
  const path = storePath(storeId, "integrations");
  const api = await backend(path);
  const checked = await api.POST("/api/v1/stores/{storeId}/integrations/{integrationId}/checks", {
    params: { path: { storeId, integrationId } },
  });
  redirectIfUnauthenticated(checked.response.status, path);
  revalidatePath(path);
}

function failure(
  status: number,
  error: { code?: string; fieldErrors?: { field: string; code: string }[]; requestId?: string } | undefined,
): FormState {
  if (status === 403) {
    return { status: "error", message: "Nie masz uprawnień do zmiany połączeń tego sklepu." };
  }
  if (status === 409) {
    return { status: "error", message: "Ten sklep ma już połączenie WooCommerce. Odśwież stronę." };
  }
  if (status === 422) {
    return { status: "error", message: "Popraw zaznaczone pola.", fieldErrors: fieldErrors(error?.fieldErrors) };
  }
  return {
    status: "error",
    message: `Nie udało się zapisać połączenia. Spróbuj ponownie (identyfikator zgłoszenia ${error?.requestId ?? "-"}).`,
  };
}
