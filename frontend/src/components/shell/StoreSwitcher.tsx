"use client";

import { Check, ChevronsUpDown } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";

import { useMessages } from "@/i18n/LocaleProvider";
import { switchStorePath } from "@/lib/store-path";

import { Icon } from "../ui/Icon";
import { MenuDisclosure } from "../ui/MenuDisclosure";

export interface StoreOption {
  storeId: string;
  name: string;
  organizationName: string;
}

/**
 * Store selection (04 §2, plan section 2.6): a button with the store and organisation that opens a list of the
 * stores the user may open. Switching keeps the section and drops resource IDs and filters of the previous store.
 * With one store there is nothing to choose, so it is shown as plain text.
 */
export function StoreSwitcher({ stores, currentStoreId }: { stores: StoreOption[]; currentStoreId: string }) {
  const pathname = usePathname();
  const t = useMessages();
  const current = stores.find((store) => store.storeId === currentStoreId);
  const label = (
    <span className="store-switcher-label">
      <span className="store-mark" aria-hidden="true">{initial(current?.name)}</span>
      <span className="store-switcher-text">
        <span className="store-switcher-name">{current?.name}</span>
        <span className="store-switcher-org">{current?.organizationName}</span>
      </span>
    </span>
  );

  if (stores.length < 2) {
    return (
      <div className="store-switcher store-switcher-single" data-testid="store-switcher">
        <span className="visually-hidden">{t.shell.store}: </span>
        {label}
      </div>
    );
  }
  return (
    <MenuDisclosure
      className="store-switcher"
      align="start"
      data-testid="store-switcher"
      trigger={
        <>
          <span className="visually-hidden">{t.shell.switchStore}. {t.shell.store}: </span>
          {label}
          <Icon icon={ChevronsUpDown} size="sm" />
        </>
      }
    >
      <ul className="menu-list" aria-label={t.shell.store}>
        {stores.map((store) => {
          const active = store.storeId === currentStoreId;
          return (
            <li key={store.storeId}>
              <Link
                href={switchStorePath(pathname, store.storeId)}
                className="menu-item"
                aria-current={active ? "true" : undefined}
              >
                <span className="store-mark" aria-hidden="true">{initial(store.name)}</span>
                <span className="store-switcher-text">
                  <span className="store-switcher-name">{store.name}</span>
                  <span className="store-switcher-org">{store.organizationName}</span>
                </span>
                {active ? <Icon icon={Check} size="sm" /> : null}
              </Link>
            </li>
          );
        })}
      </ul>
    </MenuDisclosure>
  );
}

function initial(name: string | undefined): string {
  return (name ?? "?").trim().charAt(0).toUpperCase() || "?";
}
