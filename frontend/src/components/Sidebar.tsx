"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";

import { useMessages } from "@/i18n/LocaleProvider";
import { SECTIONS, storePath } from "@/lib/store-path";

import { StoreSwitcher, type StoreOption } from "./StoreSwitcher";

export function Sidebar({ stores, currentStoreId }: { stores: StoreOption[]; currentStoreId: string }) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const t = useMessages();

  // Close the mobile drawer after navigation.
  useEffect(() => setOpen(false), [pathname]);

  return (
    <aside className="sidebar" data-open={open}>
      <div className="sidebar-top">
        <span className="product-name">E-commerce Operator</span>
        <button
          type="button"
          className="nav-toggle"
          aria-expanded={open}
          aria-controls="main-navigation"
          onClick={() => setOpen((value) => !value)}
        >
          {t.shell.menu}
        </button>
      </div>
      <div className="sidebar-body">
        <StoreSwitcher stores={stores} currentStoreId={currentStoreId} />
        <nav id="main-navigation" aria-label={t.shell.mainNavigation}>
          <ul>
            {SECTIONS.map((section) => {
              const href = storePath(currentStoreId, section);
              const active = pathname === href || pathname.startsWith(`${href}/`);
              return (
                <li key={section}>
                  <Link href={href} aria-current={active ? "page" : undefined} className="nav-link">
                    {t.sections[section]}
                  </Link>
                </li>
              );
            })}
          </ul>
          <ul className="nav-secondary">
            <li>
              <Link
                href={`/stores/${encodeURIComponent(currentStoreId)}/notifications`}
                aria-current={pathname.endsWith("/notifications") ? "page" : undefined}
                className="nav-link"
              >
                {t.notifications.title}
              </Link>
            </li>
          </ul>
        </nav>
      </div>
    </aside>
  );
}
