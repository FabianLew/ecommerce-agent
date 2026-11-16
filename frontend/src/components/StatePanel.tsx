import type { ReactNode } from "react";

import { Icon } from "@/components/ui/Icon";
import { TONE_ICON } from "@/components/ui/StatusBadge";

const TONES = { info: "info", warning: "warning", error: "danger" } as const;

/**
 * Shared empty / error / restricted state (04 §3): says what happened and what to do next. Inside a page it is a
 * section (h2); a standalone page such as sign-out or not-found passes `level={1}`.
 */
export function StatePanel({
  tone,
  title,
  description,
  children,
  level = 2,
}: {
  tone: "info" | "warning" | "error";
  title: string;
  description: string;
  children?: ReactNode;
  level?: 1 | 2;
}) {
  const Heading = level === 1 ? "h1" : "h2";
  return (
    <section className={`state-panel state-${tone}`} role={tone === "error" ? "alert" : "status"}>
      <span className="state-icon" data-tone={TONES[tone]} aria-hidden="true">
        <Icon icon={TONE_ICON[TONES[tone]]} />
      </span>
      <div className="state-body">
        <Heading className="state-title">{title}</Heading>
        <p>{description}</p>
        {children ? <div className="state-actions">{children}</div> : null}
      </div>
    </section>
  );
}

/** A standalone page outside the shell (sign-out, not found, errors): the product name above one state panel. */
export function StandaloneState({ productName, children }: { productName: string; children: ReactNode }) {
  return (
    <main className="centered">
      <div className="standalone">
        <p className="standalone-brand">
          <span className="product-mark" aria-hidden="true" />
          <span>{productName}</span>
        </p>
        {children}
      </div>
    </main>
  );
}
