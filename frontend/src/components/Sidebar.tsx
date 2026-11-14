"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";

import { SECTIONS, storePath } from "@/lib/store-path";

import { StoreSwitcher, type StoreOption } from "./StoreSwitcher";

export function Sidebar({ stores, currentStoreId }: { stores: StoreOption[]; currentStoreId: string }) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);

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
          Menu
        </button>
      </div>
      <div className="sidebar-body">
        <StoreSwitcher stores={stores} currentStoreId={currentStoreId} />
        <nav id="main-navigation" aria-label="Główna nawigacja">
          <ul>
            {SECTIONS.map((section) => {
              const href = storePath(currentStoreId, section.slug);
              const active = pathname === href || pathname.startsWith(`${href}/`);
              return (
                <li key={section.slug}>
                  <Link href={href} aria-current={active ? "page" : undefined} className="nav-link">
                    {section.label}
                  </Link>
                </li>
              );
            })}
          </ul>
        </nav>
      </div>
    </aside>
  );
}
