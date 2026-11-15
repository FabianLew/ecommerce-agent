"use server";

import { revalidatePath } from "next/cache";

import { getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

export type MappingFormState = { status: "idle" | "error" | "saved"; message?: string; variantError?: string };

function adsPath(storeId: string) {
  return `${storePath(storeId, "integrations")}/ads`;
}

/** Saves the operator's mapping of one ad, naming the version the operator saw (B-32). */
export async function saveMapping(
  storeId: string,
  adId: string,
  expectedVersion: number,
  _previous: MappingFormState,
  form: FormData,
): Promise<MappingFormState> {
  const path = adsPath(storeId);
  const t = (await getMessages()).adMappings;
  const scope = form.get("scope") === "MULTI_PRODUCT" ? "MULTI_PRODUCT" : "SINGLE_VARIANT";
  const variantIds = form.getAll("variantIds").map(String).filter(Boolean);
  const verified = form.get("verified") === "on";
  const api = await backend(path);
  const saved = await api.PUT("/api/v1/stores/{storeId}/ads/{adId}/mapping", {
    params: { path: { storeId, adId } },
    body: { scope, variantIds, verified, expectedVersion },
  });
  redirectIfUnauthenticated(saved.response.status, path);
  revalidatePath(path);
  if (saved.data) {
    return { status: "saved", message: t.saved };
  }
  return failure(saved.response.status, saved.error, t);
}

export async function revokeMapping(storeId: string, adId: string, expectedVersion: number): Promise<void> {
  const path = adsPath(storeId);
  const api = await backend(path);
  const revoked = await api.POST("/api/v1/stores/{storeId}/ads/{adId}/mapping/revocation", {
    params: { path: { storeId, adId } },
    body: { expectedVersion },
  });
  redirectIfUnauthenticated(revoked.response.status, path);
  revalidatePath(path);
}

function failure(
  status: number,
  error: { fieldErrors?: { field: string; code: string }[]; requestId?: string } | undefined,
  t: Awaited<ReturnType<typeof getMessages>>["adMappings"],
): MappingFormState {
  if (status === 403) {
    return { status: "error", message: t.noPermission };
  }
  if (status === 409) {
    return { status: "error", message: t.changed };
  }
  if (status === 422) {
    const code = error?.fieldErrors?.[0]?.code ?? "REQUIRED";
    return { status: "error", variantError: t.fieldMessages[code] ?? t.fieldMessages.REQUIRED };
  }
  return { status: "error", message: t.saveFailed(error?.requestId ?? "-") };
}
