"use client";

import { Menu } from "lucide-react";
import { usePathname } from "next/navigation";
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from "react";

import { useMessages } from "@/i18n/LocaleProvider";

import { Icon } from "../ui/Icon";

export const NAV_TOGGLE_ID = "nav-toggle";
export const NAV_PANEL_ID = "main-sidebar";

interface ShellNav {
  open: boolean;
  setOpen: (open: boolean) => void;
  close: () => void;
}

const Context = createContext<ShellNav>({ open: false, setOpen: () => {}, close: () => {} });

/**
 * State of the navigation drawer below 900 px (plan section 2.6). The menu button lives in the top bar and the drawer
 * is the sidebar, so both read the same state. The drawer closes after every navigation.
 */
export function ShellNavProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const pathname = usePathname();
  useEffect(() => setOpen(false), [pathname]);

  const close = useCallback(() => {
    setOpen(false);
    document.getElementById(NAV_TOGGLE_ID)?.focus();
  }, []);
  const value = useMemo(() => ({ open, setOpen, close }), [open, close]);

  return (
    <Context.Provider value={value}>
      {children}
      {open ? <div className="drawer-backdrop" aria-hidden="true" onClick={close} /> : null}
    </Context.Provider>
  );
}

export function useShellNav(): ShellNav {
  return useContext(Context);
}

/** The menu button, shown only below 900 px. */
export function NavToggle() {
  const { open, setOpen } = useShellNav();
  const t = useMessages();
  return (
    <button
      type="button"
      id={NAV_TOGGLE_ID}
      className="button ghost nav-toggle"
      aria-expanded={open}
      aria-controls={NAV_PANEL_ID}
      aria-label={t.shell.openMenu}
      onClick={() => setOpen(!open)}
    >
      <Icon icon={Menu} />
    </button>
  );
}
