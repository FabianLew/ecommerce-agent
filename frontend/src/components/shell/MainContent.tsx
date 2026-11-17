"use client";

import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

import { shellLocation } from "@/lib/shell-location";

/** The content column, as wide as the page needs (plan section 2.3) and centred on wide screens. */
export function MainContent({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  return (
    <main id="content" className="content" data-width={shellLocation(pathname).container} tabIndex={-1}>
      {children}
    </main>
  );
}
