"use server";

import { revalidatePath } from "next/cache";

import { backend, redirectIfUnauthenticated } from "@/lib/api";

type Step = "CONNECT_STORE" | "CONNECT_TOOLS" | "BUSINESS_DATA" | "START_MONITORING";
type Acknowledgement = "SKIP_META" | "MAPPINGS_LATER";

/**
 * Stores the position and the acknowledgements only; whether a step is complete is decided by the backend from
 * the real state (ADR-0018).
 */
export async function updateOnboarding(
  storeId: string,
  currentStep: Step,
  acknowledgements: Acknowledgement[],
  expectedVersion: number,
): Promise<void> {
  const path = `/stores/${encodeURIComponent(storeId)}/onboarding`;
  const api = await backend(path);
  const updated = await api.PATCH("/api/v1/stores/{storeId}/onboarding", {
    params: { path: { storeId } },
    body: { currentStep, acknowledgements, expectedVersion },
  });
  redirectIfUnauthenticated(updated.response.status, path);
  revalidatePath(path);
}
