"use server";

import { revalidatePath } from "next/cache";

import { getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

export type StopFormState = { status: "idle" | "error" | "saved"; message?: string };

/** Stops or resumes changes from the version the person saw (ADR-0020 point 7). */
export async function changeMutationStop(
  storeId: string,
  version: number,
  stopped: boolean,
  _previous: StopFormState,
  form: FormData,
): Promise<StopFormState> {
  const path = storePath(storeId, "settings");
  const t = (await getMessages()).mutationStop;
  const reason = String(form.get("reason") ?? "").trim();
  if (stopped && !reason) {
    return { status: "error", message: t.reasonRequired };
  }
  const api = await backend(path);
  const result = await api.POST("/api/v1/stores/{storeId}/mutation-stop", {
    params: { path: { storeId }, header: { "If-Match": `"stop-${version}"` } },
    body: { stopped, reason: reason || undefined },
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
    const code = result.error?.fieldErrors?.[0]?.code;
    return { status: "error", message: code === "REASON_TOO_LONG" ? t.reasonTooLong : t.reasonRequired };
  }
  return { status: "error", message: t.failed(result.error?.requestId ?? "-") };
}
