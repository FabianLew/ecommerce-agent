import type { HTMLAttributes, ReactNode } from "react";

import { Icon } from "./Icon";
import { TONE_ICON } from "./StatusBadge";
import type { Tone } from "./tones";

/**
 * A message inside a page: blockers, warnings, confirmations (plan section 2.7). The caller chooses the role:
 * `alert` only for an error that appears after an action, `status` for results, `note` for standing information.
 */
export function Callout({
  tone,
  title,
  children,
  ...rest
}: {
  tone: Tone;
  title?: ReactNode;
  children?: ReactNode;
} & Omit<HTMLAttributes<HTMLDivElement>, "className" | "title">) {
  return (
    <div className={`callout tone-${tone}`} {...rest}>
      <Icon icon={TONE_ICON[tone]} />
      <div className="callout-body">
        {title ? <strong className="callout-title">{title}</strong> : null}
        {children ? <div>{children}</div> : null}
      </div>
    </div>
  );
}
