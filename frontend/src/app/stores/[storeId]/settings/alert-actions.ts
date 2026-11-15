"use server";

import { revalidatePath } from "next/cache";

import { getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

export type AlertFormState = { status: "idle" | "error" | "saved"; message?: string };

/** Sets or clears the store's alert deputy from the version the person saw (ADR-0026 point 5). */
export async function changeAlertDeputy(
  storeId: string,
  version: number,
  _previous: AlertFormState,
  form: FormData,
): Promise<AlertFormState> {
  const path = storePath(storeId, "settings");
  const t = (await getMessages()).alertDeputy;
  const deputy = String(form.get("deputy") ?? "");
  const api = await backend(path);
  const result = await api.PUT("/api/v1/stores/{storeId}/alert-settings", {
    params: { path: { storeId }, header: { "If-Match": `"alert-setting-${version}"` } },
    body: { deputyUserId: deputy || undefined },
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
  if (status === 422) {
    return { status: "error", message: t.notApprover };
  }
  return { status: "error", message: t.failed(result.error?.requestId ?? "-") };
}
