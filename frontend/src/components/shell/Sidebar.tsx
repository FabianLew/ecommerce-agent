"use client";

import {
  ChartColumn,
  History,
  Inbox,
  LayoutDashboard,
  ListChecks,
  type LucideIcon,
  Plug,
  Settings,
  X,
} from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { type KeyboardEvent, useEffect, useRef } from "react";

import { useMessages } from "@/i18n/LocaleProvider";
import { shellLocation } from "@/lib/shell-location";
import { NAV_GROUPS, type SectionSlug, storePath } from "@/lib/store-path";

import { Icon } from "../ui/Icon";
import { NAV_PANEL_ID, useShellNav } from "./ShellNav";
import { type StoreOption, StoreSwitcher } from "./StoreSwitcher";

const SECTION_ICON: Partial<Record<SectionSlug, LucideIcon>> = {
  dashboard: LayoutDashboard,
  decisions: Inbox,
  analytics: ChartColumn,
  activity: History,
  integrations: Plug,
  settings: Settings,
};

export type AdLevel = "OFF" | "PROPOSE_ONLY" | "APPROVAL_REQUIRED";

/** What the shell knows about the store; every field is optional because a read may fail or be forbidden. */
export interface ShellStatus {
  toApprove?: number;
  /** Present only while monitoring has not been started. */
  setup?: { done: number; total: number };
  monitoringStarted?: boolean;
  adLevel?: AdLevel;
  changesStopped: boolean;
}

export function Sidebar({
  stores,
  currentStoreId,
  status,
}: {
  stores: StoreOption[];
  currentStoreId: string;
  status: ShellStatus;
}) {
  const pathname = usePathname();
  const { open, close } = useShellNav();
  const t = useMessages();
  const panel = useRef<HTMLElement>(null);
  const location = shellLocation(pathname);

  // Drawer below 900 px: move focus into it on open, keep it there, and lock the page behind it.
  useEffect(() => {
    if (!open) return;
    panel.current?.querySelector<HTMLElement>(".drawer-close")?.focus();
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previous;
    };
  }, [open]);

  function onKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (!open) return;
    if (event.key === "Escape") {
      event.preventDefault();
      close();
      return;
    }
    if (event.key !== "Tab" || !panel.current) return;
    const focusable = Array.from(
      panel.current.querySelectorAll<HTMLElement>("a[href], button:not([disabled]), summary, select, input"),
    ).filter((element) => element.offsetParent !== null);
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  }

  return (
    <aside
      ref={panel}
      id={NAV_PANEL_ID}
      className="sidebar"
      data-open={open}
      onKeyDown={onKeyDown}
    >
      <div className="sidebar-top">
        <span className="product-mark" aria-hidden="true" />
        <span className="product-name">E-commerce Operator</span>
        <button type="button" className="button ghost drawer-close" aria-label={t.shell.closeMenu} onClick={close}>
          <Icon icon={X} />
        </button>
      </div>
      <StoreSwitcher stores={stores} currentStoreId={currentStoreId} />
      <nav className="sidebar-nav" aria-label={t.shell.mainNavigation}>
        {NAV_GROUPS.map((group) => (
          <div key={group.id} className="nav-group">
            {group.id === "main" ? null : (
              <p className="nav-group-label" id={`nav-${group.id}`}>{t.shell.navGroups[group.id]}</p>
            )}
            <ul aria-labelledby={group.id === "main" ? undefined : `nav-${group.id}`}>
              {group.sections.map((section) => {
                const glyph = SECTION_ICON[section];
                const count = section === "decisions" ? status.toApprove : undefined;
                return (
                  <li key={section}>
                    <Link
                      href={storePath(currentStoreId, section)}
                      aria-current={location.section === section ? "page" : undefined}
                      className="nav-link"
                    >
                      {glyph ? <Icon icon={glyph} /> : null}
                      <span className="nav-label">{t.sections[section]}</span>
                      {count ? (
                        <span className="nav-count" data-testid="nav-to-approve">
                          <span aria-hidden="true">{count}</span>
                          <span className="visually-hidden">{t.shell.toApprove(count)}</span>
                        </span>
                      ) : null}
                    </Link>
                  </li>
                );
              })}
              {group.id === "setup" && status.setup ? (
                <li>
                  <Link
                    href={`/stores/${encodeURIComponent(currentStoreId)}/onboarding`}
                    aria-current={location.page === "onboarding" ? "page" : undefined}
                    className="nav-link"
                  >
                    <Icon icon={ListChecks} />
                    <span className="nav-label">{t.shell.setup}</span>
                    <span className="nav-progress" data-testid="nav-setup-progress">
                      <span aria-hidden="true">{status.setup.done}/{status.setup.total}</span>
                      <span className="visually-hidden">{t.shell.setupSteps(status.setup.done, status.setup.total)}</span>
                    </span>
                  </Link>
                </li>
              ) : null}
            </ul>
          </div>
        ))}
      </nav>
      <OperatingState status={status} />
    </aside>
  );
}

/** The store's operating state at the bottom of the sidebar (plan section 2.6): only states the API reports. */
function OperatingState({ status }: { status: ShellStatus }) {
  const t = useMessages();
  const lines: { tone: "success" | "neutral" | "danger"; text: string }[] = [];
  if (status.changesStopped) lines.push({ tone: "danger", text: t.shell.changesStopped });
  if (status.monitoringStarted !== undefined) {
    lines.push(
      status.monitoringStarted
        ? { tone: "success", text: t.shell.monitoringOn }
        : { tone: "neutral", text: t.shell.monitoringOff },
    );
  }
  if (status.adLevel) lines.push({ tone: "neutral", text: t.shell.adPauses[status.adLevel] });
  if (lines.length === 0) return null;
  return (
    <section className="operating-state" aria-label={t.shell.operatingState} data-testid="operating-state">
      <ul>
        {lines.map((line) => (
          <li key={line.text} className="operating-line" data-tone={line.tone}>
            <span className="status-dot" aria-hidden="true" />
            {line.text}
          </li>
        ))}
      </ul>
    </section>
  );
}
