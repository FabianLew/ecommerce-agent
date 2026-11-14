import type { ReactNode } from "react";

import type { MeResponse, StoreResponse } from "@/lib/api";

import { Sidebar } from "./Sidebar";

/** Layout from 04 §2: sidebar with store selection and eight sections, header with store context. */
export function AppShell({
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
  return (
    <div className="shell">
      <a href="#content" className="skip-link">Przejdź do treści</a>
      <Sidebar
        currentStoreId={store.storeId}
        stores={stores.map(({ storeId, name, organizationName }) => ({ storeId, name, organizationName }))}
      />
      <div className="main-column">
        <header className="topbar">
          <div className="store-context">
            <span className="store-name">{store.name}</span>
            <span className="muted">Strefa czasu: {store.timezone}</span>
          </div>
          <div className="user-menu">
            <span className="muted" data-testid="current-user">{me.displayName ?? me.email ?? "Użytkownik"}</span>
            <form action="/auth/logout" method="post">
              <button type="submit" className="button secondary">Wyloguj</button>
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
