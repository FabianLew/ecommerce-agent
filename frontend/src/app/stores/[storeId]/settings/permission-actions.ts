"use server";

import { revalidatePath } from "next/cache";

import type { components } from "@/api/schema";
import { getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

type Level = components["schemas"]["ActionPermissionRequest"]["level"];

export type PermissionFormState = { status: "idle" | "error" | "saved"; message?: string };

const ORDER: Level[] = ["OFF", "PROPOSE_ONLY", "APPROVAL_REQUIRED"];

/** Changes the store's ad pause level from the version the person saw (ADR-0025 point 2). */
export async function changeActionPermission(
  storeId: string,
  version: number,
  current: Level,
  _previous: PermissionFormState,
  form: FormData,
): Promise<PermissionFormState> {
  const path = storePath(storeId, "settings");
  const t = (await getMessages()).actionPermission;
  const level = String(form.get("level") ?? "") as Level;
  if (!ORDER.includes(level)) {
    return { status: "error", message: t.failed("-") };
  }
  const reason = String(form.get("reason") ?? "").trim();
  if (ORDER.indexOf(level) > ORDER.indexOf(current) && !reason) {
    return { status: "error", message: t.reasonRequired };
  }
  const api = await backend(path);
  const result = await api.PUT("/api/v1/stores/{storeId}/action-permissions/{actionType}", {
    params: { path: { storeId, actionType: "PAUSE_AD" }, header: { "If-Match": `"permission-${version}"` } },
    body: { level, reason: reason || undefined },
  });
  redirectIfUnauthenticated(result.response.status, path);
  revalidatePath(path);
  if (result.data) {
    return { status: "saved", message: t.done };
  }
  const status = result.response.status;
  if (status === 403) {
    return { status: "error", message: t.noPermission };
  }
  if (status === 412 || status === 428) {
    return { status: "error", message: t.changed };
  }
  if (status === 409) {
    const code = result.error?.code ?? "";
    return { status: "error", message: t.notReady[code] ?? t.failed(result.error?.requestId ?? "-") };
  }
  if (status === 422) {
    const code = result.error?.fieldErrors?.[0]?.code;
    return { status: "error", message: code === "REASON_TOO_LONG" ? t.reasonTooLong : t.reasonRequired };
  }
  return { status: "error", message: t.failed(result.error?.requestId ?? "-") };
}
