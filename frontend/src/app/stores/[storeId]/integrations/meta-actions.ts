"use server";

import { revalidatePath } from "next/cache";

import { getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

export type MetaFormState = {
  status: "idle" | "error" | "saved";
  message?: string;
  fieldErrors?: Partial<Record<"accountId" | "token", string>>;
  /** The account id is kept after a failed submit; the token never is. */
  accountId?: string;
};

/**
 * Creates the Meta connection if needed, stores the token (sent only server-to-server) and runs the check. The
 * token is not kept in the returned state, the session or the URL (ADR-0017).
 */
export async function connectMeta(storeId: string, _previous: MetaFormState, form: FormData): Promise<MetaFormState> {
  const path = storePath(storeId, "integrations");
  const t = (await getMessages()).meta;
  const accountId = String(form.get("accountId") ?? "").trim();
  const token = String(form.get("token") ?? "").trim();
  let connectionId = String(form.get("connectionId") ?? "");
  const missing: MetaFormState["fieldErrors"] = {};
  if (!connectionId && !accountId) {
    missing.accountId = t.fieldMessages.REQUIRED;
  }
  if (!token) {
    missing.token = t.fieldMessages.REQUIRED;
  }
  if (missing.accountId || missing.token) {
    return { status: "error", fieldErrors: missing, accountId };
  }

  const api = await backend(path);
  if (!connectionId) {
    const created = await api.POST("/api/v1/stores/{storeId}/meta-connection", {
      params: { path: { storeId } },
      body: { accountId },
    });
    redirectIfUnauthenticated(created.response.status, path);
    if (!created.data) {
      return { ...failure(created.error?.fieldErrors, t), accountId };
    }
    connectionId = created.data.id;
  }
  const saved = await api.POST("/api/v1/stores/{storeId}/meta-connection/{connectionId}/token", {
    params: { path: { storeId, connectionId } },
    body: { token },
  });
  redirectIfUnauthenticated(saved.response.status, path);
  if (!saved.data) {
    revalidatePath(path, "layout");
    return failure(saved.error?.fieldErrors, t);
  }
  const checked = await api.POST("/api/v1/stores/{storeId}/meta-connection/{connectionId}/checks", {
    params: { path: { storeId, connectionId } },
  });
  redirectIfUnauthenticated(checked.response.status, path);
  revalidatePath(path, "layout");
  return checked.data?.status === "CONNECTED"
    ? { status: "saved", message: t.connected }
    : { status: "error", message: t.checkFailed };
}

export async function checkMeta(storeId: string, connectionId: string): Promise<void> {
  const path = storePath(storeId, "integrations");
  const api = await backend(path);
  const checked = await api.POST("/api/v1/stores/{storeId}/meta-connection/{connectionId}/checks", {
    params: { path: { storeId, connectionId } },
  });
  redirectIfUnauthenticated(checked.response.status, path);
  revalidatePath(path, "layout");
}

function failure(
  errors: { field: string; code: string }[] | undefined,
  t: Awaited<ReturnType<typeof getMessages>>["meta"],
): MetaFormState {
  const fieldErrors: MetaFormState["fieldErrors"] = {};
  for (const error of errors ?? []) {
    if (error.field === "accountId" || error.field === "token") {
      fieldErrors[error.field] = t.fieldMessages[error.code] ?? t.fieldMessages.REQUIRED;
    }
  }
  return { status: "error", fieldErrors, message: errors?.length ? undefined : t.readOnly };
}
