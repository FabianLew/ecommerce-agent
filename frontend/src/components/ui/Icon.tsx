import type { LucideIcon } from "lucide-react";

/**
 * The one entry point for icons (ADR-0029, decision D-2). Icons are decorative: the text next to them carries the
 * meaning, so they are hidden from assistive technology. Size and stroke are fixed here so every screen matches.
 */
export function Icon({ icon: Glyph, size = "md" }: { icon: LucideIcon; size?: "sm" | "md" }) {
  return (
    <Glyph
      className={size === "sm" ? "icon icon-sm" : "icon"}
      size={size === "sm" ? 14 : 16}
      strokeWidth={1.75}
      aria-hidden="true"
      focusable="false"
    />
  );
}
