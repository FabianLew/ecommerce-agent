"use client";

import { usePathname, useRouter } from "next/navigation";
import { useId, useTransition } from "react";

import { useMessages } from "@/i18n/LocaleProvider";
import { switchStorePath } from "@/lib/store-path";

export interface StoreOption {
  storeId: string;
  name: string;
  organizationName: string;
}

/**
 * Store selection (04 §2). A native select keeps keyboard and screen reader behaviour predictable.
 * Switching keeps the section and drops resource IDs and filters of the previous store.
 */
export function StoreSwitcher({ stores, currentStoreId }: { stores: StoreOption[]; currentStoreId: string }) {
  const router = useRouter();
  const pathname = usePathname();
  const id = useId();
  const [pending, startTransition] = useTransition();
  const t = useMessages();

  return (
    <div className="store-switcher">
      <label htmlFor={id}>{t.shell.store}</label>
      <select
        id={id}
        value={currentStoreId}
        disabled={pending || stores.length < 2}
        aria-busy={pending}
        onChange={(event) => {
          const target = event.target.value;
          startTransition(() => router.push(switchStorePath(pathname, target)));
        }}
      >
        {stores.map((store) => (
          <option key={store.storeId} value={store.storeId}>
            {store.name} · {store.organizationName}
          </option>
        ))}
      </select>
    </div>
  );
}
