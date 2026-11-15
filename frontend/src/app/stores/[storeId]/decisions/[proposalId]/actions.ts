"use server";

import { revalidatePath } from "next/cache";

import { getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { decisionPath } from "@/lib/store-path";

export type DecisionFormState = { status: "idle" | "error" | "saved"; message?: string };

/**
 * Approves or rejects one revision (ADR-0020 point 2). The idempotency key comes from the open dialog, so a double
 * click or a retry repeats the same command instead of creating another (UX-05).
 */
export async function decide(
  storeId: string,
  proposalId: string,
  revision: number,
  etag: string,
  kind: "approve" | "reject",
  _previous: DecisionFormState,
  form: FormData,
): Promise<DecisionFormState> {
  const path = decisionPath(storeId, proposalId);
  const t = (await getMessages()).approval;
  const key = String(form.get("idempotencyKey") ?? "");
  const params = { path: { storeId, proposalId }, header: { "If-Match": etag, "Idempotency-Key": key } };
  const api = await backend(path);
  const result =
    kind === "approve"
      ? await api.POST("/api/v1/stores/{storeId}/decisions/{proposalId}/approvals", {
          params,
          body: { revision },
        })
      : await api.POST("/api/v1/stores/{storeId}/decisions/{proposalId}/rejections", {
          params,
          body: { revision, reason: String(form.get("reason") ?? "").trim() || undefined },
        });
  redirectIfUnauthenticated(result.response.status, path);
  revalidatePath(path);
  if (result.data) {
    return { status: "saved", message: t.accepted };
  }
  const error = result.error as { code?: string; requestId?: string } | undefined;
  const code = result.response.status === 403 ? "FORBIDDEN" : error?.code;
  return { status: "error", message: (code && t.errors[code]) || t.failed(error?.requestId ?? "-") };
}
