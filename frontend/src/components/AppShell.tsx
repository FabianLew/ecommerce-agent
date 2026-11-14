import type { ReactNode } from "react";

import { LanguageSwitcher } from "@/i18n/LanguageSwitcher";
import { getMessages } from "@/i18n/server";
import type { MeResponse, StoreResponse } from "@/lib/api";

import { Sidebar } from "./Sidebar";

/** Layout from 04 §2: sidebar with store selection and eight sections, header with store context. */
export async function AppShell({
  me,
  stores,
  store,
  children,
}: {
  me: MeResponse;
  stores: StoreResponse[];
  store: StoreResponse;
  children: ReactNode;
}) {
  const t = await getMessages();
  return (
    <div className="shell">
      <a href="#content" className="skip-link">{t.shell.skipToContent}</a>
      <Sidebar
        currentStoreId={store.storeId}
        stores={stores.map(({ storeId, name, organizationName }) => ({ storeId, name, organizationName }))}
      />
      <div className="main-column">
        <header className="topbar">
          <div className="store-context">
            <span className="store-name">{store.name}</span>
            <span className="muted">{t.shell.timezone(store.timezone)}</span>
          </div>
          <div className="user-menu">
            <span className="muted" data-testid="current-user">{me.displayName ?? me.email ?? t.common.user}</span>
            <LanguageSwitcher />
            <form action="/auth/logout" method="post">
              <button type="submit" className="button secondary">{t.common.signOut}</button>
            </form>
          </div>
        </header>
        <main id="content" className="content">
          {children}
        </main>
      </div>
    </div>
  );
}
