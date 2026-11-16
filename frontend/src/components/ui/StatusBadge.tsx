import { CircleAlert, CircleCheck, CircleDashed, CircleX, Info, type LucideIcon } from "lucide-react";
import type { HTMLAttributes, ReactNode } from "react";

import { Icon } from "./Icon";
import type { Tone } from "./tones";

export const TONE_ICON: Record<Tone, LucideIcon> = {
  success: CircleCheck,
  warning: CircleAlert,
  danger: CircleX,
  info: Info,
  neutral: CircleDashed,
};

/**
 * A status as icon + text + colour (04 §2, plan section 2.7). Renders a `span` by default; pass `as="p"` where the
 * badge stands alone as a live status. Extra attributes (role, data-testid, aria-*) pass through.
 */
export function StatusBadge({
  tone,
  children,
  as: Tag = "span",
  icon = true,
  ...rest
}: {
  tone: Tone;
  children: ReactNode;
  as?: "span" | "p";
  icon?: boolean;
} & Omit<HTMLAttributes<HTMLElement>, "className">) {
  return (
    <Tag className={`badge tone-${tone}`} {...rest}>
      {icon ? <Icon icon={TONE_ICON[tone]} size="sm" /> : null}
      {children}
    </Tag>
  );
}
