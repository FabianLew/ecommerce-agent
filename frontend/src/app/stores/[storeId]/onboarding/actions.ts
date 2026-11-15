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

/** Starts monitoring (ADR-0025); the backend refuses it until every earlier step is complete. */
export async function startMonitoring(storeId: string, expectedVersion: number): Promise<void> {
  const path = `/stores/${encodeURIComponent(storeId)}/onboarding`;
  const api = await backend(path);
  const started = await api.POST("/api/v1/stores/{storeId}/onboarding/monitoring-start", {
    params: { path: { storeId } },
    body: { expectedVersion },
  });
  redirectIfUnauthenticated(started.response.status, path);
  revalidatePath(path);
}
