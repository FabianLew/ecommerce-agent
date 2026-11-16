import { Bell, LogOut, OctagonPause } from "lucide-react";
import Link from "next/link";
import type { ReactNode } from "react";

import { LanguageSwitcher } from "@/i18n/LanguageSwitcher";
import { getMessages } from "@/i18n/server";
import type { MeResponse, StoreResponse } from "@/lib/api";
import { storePath } from "@/lib/store-path";

import { Callout } from "../ui/Callout";
import { Icon } from "../ui/Icon";
import { MenuDisclosure } from "../ui/MenuDisclosure";
import { Breadcrumbs } from "./Breadcrumbs";
import { MainContent } from "./MainContent";
import { NavToggle, ShellNavProvider } from "./ShellNav";
import { type ShellStatus, Sidebar } from "./Sidebar";

export interface MutationStopState {
  stopped: boolean;
  globallyStopped: boolean;
  reason?: string;
}

/**
 * Layout from 04 §2 and plan section 2.6: sidebar with the store switcher, grouped navigation and the operating
 * state; top bar with breadcrumbs, notifications and the account menu; the safety banner while changes are stopped.
 */
export async function AppShell({
  me,
  stores,
  store,
  status,
  unread,
  stop,
  children,
}: {
  me: MeResponse;
  stores: StoreResponse[];
  store: StoreResponse;
  status: ShellStatus;
  unread?: number;
  stop?: MutationStopState;
  children: ReactNode;
}) {
  const t = await getMessages();
  const name = me.displayName ?? me.email ?? t.common.user;
  const notificationsHref = `/stores/${encodeURIComponent(store.storeId)}/notifications`;
  const roles = store.roles.map((role) => t.store.roleLabels[role] ?? role).join(", ");

  return (
    <ShellNavProvider>
      <div className="shell">
        <a href="#content" className="skip-link">{t.shell.skipToContent}</a>
        <Sidebar
          currentStoreId={store.storeId}
          stores={stores.map(({ storeId, name: storeName, organizationName }) => ({
            storeId,
            name: storeName,
            organizationName,
          }))}
          status={status}
        />
        <div className="main-column">
          <header className="topbar">
            <div className="topbar-start">
              <NavToggle />
              <Breadcrumbs />
            </div>
            <div className="topbar-end">
              <Link
                href={notificationsHref}
                className="button ghost icon-button notifications-button"
                aria-label={unread ? t.shell.notificationsUnread(unread) : t.shell.notifications}
                data-testid="notifications-button"
              >
                <Icon icon={Bell} />
                {unread ? <span className="notifications-count" aria-hidden="true">{unread > 99 ? "99+" : unread}</span> : null}
              </Link>
              <MenuDisclosure
                className="user-menu"
                data-testid="user-menu"
                trigger={
                  <>
                    <span className="avatar" aria-hidden="true">{initials(name)}</span>
                    <span className="user-name" data-testid="current-user">{name}</span>
                    <span className="visually-hidden">, {t.shell.account}</span>
                  </>
                }
              >
                <div className="menu-section">
                  <p className="menu-caption">{t.shell.signedInAs}</p>
                  <p className="menu-strong">{name}</p>
                  {me.email && me.email !== name ? <p className="menu-meta">{me.email}</p> : null}
                </div>
                <dl className="menu-section menu-facts">
                  <div>
                    <dt>{t.shell.roleHere}</dt>
                    <dd>{roles || t.common.none}</dd>
                  </div>
                  <div>
                    <dt>{t.store.timezone}</dt>
                    <dd>{store.timezone}</dd>
                  </div>
                </dl>
                <div className="menu-section menu-actions">
                  <LanguageSwitcher />
                  <form action="/auth/logout" method="post">
                    <button type="submit" className="button ghost menu-signout">
                      <Icon icon={LogOut} />
                      {t.common.signOut}
                    </button>
                  </form>
                </div>
              </MenuDisclosure>
            </div>
          </header>
          {stop && (stop.stopped || stop.globallyStopped) ? (
            <div className="safety-banner" data-testid="safety-banner">
              <Callout
                tone="danger"
                title={stop.globallyStopped ? t.shell.stopBanner.globalTitle : t.shell.stopBanner.storeTitle}
              >
                <p>{stop.globallyStopped ? t.mutationStop.globallyStopped : t.shell.stopBanner.storeText}</p>
                {stop.reason ? <p>{t.shell.stopBanner.reason(stop.reason)}</p> : null}
                <p>
                  <Link href={storePath(store.storeId, "settings")}>
                    <Icon icon={OctagonPause} size="sm" /> {t.shell.stopBanner.open}
                  </Link>
                </p>
              </Callout>
            </div>
          ) : null}
          <MainContent>{children}</MainContent>
        </div>
      </div>
    </ShellNavProvider>
  );
}

function initials(name: string): string {
  const parts = name.split(/[\s@._-]+/).filter(Boolean);
  return ((parts[0]?.[0] ?? "") + (parts[1]?.[0] ?? "")).toUpperCase() || "?";
}
