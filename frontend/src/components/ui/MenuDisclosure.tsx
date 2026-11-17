"use client";

import { usePathname } from "next/navigation";
import { type ReactNode, useEffect, useRef } from "react";

/**
 * A small popover menu on a native `details` element (plan section 2.6): it opens and closes without JavaScript,
 * keeps keyboard and screen reader behaviour of a disclosure, and adds only closing on Escape, on a click outside
 * and after navigation.
 */
export function MenuDisclosure({
  trigger,
  triggerLabel,
  align = "end",
  className,
  children,
  ...rest
}: {
  trigger: ReactNode;
  triggerLabel?: string;
  align?: "start" | "end";
  className?: string;
  children: ReactNode;
  "data-testid"?: string;
}) {
  const ref = useRef<HTMLDetailsElement>(null);
  const pathname = usePathname();

  useEffect(() => {
    if (ref.current) ref.current.open = false;
  }, [pathname]);

  useEffect(() => {
    function onPointerDown(event: PointerEvent) {
      const details = ref.current;
      if (details?.open && !details.contains(event.target as Node)) details.open = false;
    }
    function onKeyDown(event: KeyboardEvent) {
      const details = ref.current;
      if (event.key === "Escape" && details?.open) {
        details.open = false;
        details.querySelector("summary")?.focus();
      }
    }
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, []);

  return (
    <details ref={ref} className={["menu", className].filter(Boolean).join(" ")} {...rest}>
      <summary className="menu-trigger" aria-label={triggerLabel}>
        {trigger}
      </summary>
      <div className={`menu-panel menu-panel-${align}`}>{children}</div>
    </details>
  );
}
