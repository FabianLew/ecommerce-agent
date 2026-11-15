"use server";

import { revalidatePath } from "next/cache";

import { backend, redirectIfUnauthenticated } from "@/lib/api";

export async function markNotificationRead(storeId: string, notificationId: string): Promise<void> {
  const path = `/stores/${encodeURIComponent(storeId)}/notifications`;
  const api = await backend(path);
  const result = await api.POST("/api/v1/stores/{storeId}/notifications/{notificationId}/read", {
    params: { path: { storeId, notificationId } },
  });
  redirectIfUnauthenticated(result.response.status, path);
  revalidatePath(path);
}
