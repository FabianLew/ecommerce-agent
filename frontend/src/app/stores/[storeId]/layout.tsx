import Link from "next/link";
import type { ReactNode } from "react";

import { AppShell } from "@/components/shell/AppShell";
import type { ShellStatus } from "@/components/shell/Sidebar";
import { StandaloneState, StatePanel } from "@/components/StatePanel";
import { PRODUCT_NAME } from "@/lib/brand";
import { getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";

export const dynamic = "force-dynamic";

/**
 * Store context for every page under /stores/:storeId. The backend decides access; an unknown or
 * foreign store shows a safe "not available" state and never switches the store automatically (04 §2).
 */
export default async function StoreLayout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ storeId: string }>;
}) {
  const { storeId } = await params;
  const returnTo = `/stores/${encodeURIComponent(storeId)}/dashboard`;
  const api = await backend(returnTo);
  const path = { params: { path: { storeId } } };
  // Shell reads (plan section 8): four light reads in parallel with the store context. Each one is optional: a
  // failed or forbidden read leaves its counter or state out and never blocks the page.
  const [me, stores, store, decisions, notifications, stop, onboarding] = await Promise.all([
    api.GET("/api/v1/me"),
    api.GET("/api/v1/stores"),
    api.GET("/api/v1/stores/{storeId}", path),
    api.GET("/api/v1/stores/{storeId}/decisions", { params: { path: { storeId }, query: { limit: 1 } } }),
    api.GET("/api/v1/stores/{storeId}/notifications", { params: { path: { storeId }, query: { limit: 1 } } }),
    api.GET("/api/v1/stores/{storeId}/mutation-stop", path),
    api.GET("/api/v1/stores/{storeId}/onboarding", path),
  ]);
  for (const result of [me, stores, store]) {
    redirectIfUnauthenticated(result.response.status, returnTo);
  }
  if (store.response.status === 404 || store.response.status === 403) {
    const t = await getMessages();
    return (
      <StandaloneState productName={PRODUCT_NAME}>
        <StatePanel
          level={1}
          tone="warning"
          title={t.store.unavailableTitle}
          description={t.store.unavailableDescription}
        >
          <Link href="/" className="button primary">{t.common.goToStores}</Link>
        </StatePanel>
      </StandaloneState>
    );
  }
  if (!me.data || !stores.data || !store.data) {
    throw new Error(`Store context failed (requestId ${store.error?.requestId ?? stores.error?.requestId ?? "-"})`);
  }
  const steps = onboarding.data?.steps ?? [];
  const monitoringStarted = onboarding.data ? Boolean(onboarding.data.monitoring.startedAt) : undefined;
  const status: ShellStatus = {
    toApprove: decisions.data?.counts.toApprove,
    setup:
      monitoringStarted === false && steps.length > 0
        ? { done: steps.filter((step) => step.complete).length, total: steps.length }
        : undefined,
    monitoringStarted,
    adLevel: onboarding.data?.monitoring.ads,
    changesStopped: Boolean(stop.data?.stopped || stop.data?.globallyStopped),
  };
  return (
    <AppShell
      me={me.data}
      stores={stores.data.items}
      store={store.data}
      status={status}
      unread={notifications.data?.unreadCount}
      stop={stop.data}
    >
      {children}
    </AppShell>
  );
}
