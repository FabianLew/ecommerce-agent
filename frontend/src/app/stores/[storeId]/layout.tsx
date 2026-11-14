import Link from "next/link";
import type { ReactNode } from "react";

import { AppShell } from "@/components/AppShell";
import { StatePanel } from "@/components/StatePanel";
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
  const [me, stores, store] = await Promise.all([
    api.GET("/api/v1/me"),
    api.GET("/api/v1/stores"),
    api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } }),
  ]);
  for (const result of [me, stores, store]) {
    redirectIfUnauthenticated(result.response.status, returnTo);
  }
  if (store.response.status === 404 || store.response.status === 403) {
    const t = await getMessages();
    return (
      <main className="centered">
        <StatePanel
          tone="warning"
          title={t.store.unavailableTitle}
          description={t.store.unavailableDescription}
        >
          <Link href="/" className="button primary">{t.common.goToStores}</Link>
        </StatePanel>
      </main>
    );
  }
  if (!me.data || !stores.data || !store.data) {
    throw new Error(`Store context failed (requestId ${store.error?.requestId ?? stores.error?.requestId ?? "-"})`);
  }
  return (
    <AppShell me={me.data} stores={stores.data.items} store={store.data}>
      {children}
    </AppShell>
  );
}
