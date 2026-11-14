import type { ReactNode } from "react";

/** Shared empty / error / restricted state (04 §3): says what happened and what to do next. */
export function StatePanel({
  tone,
  title,
  description,
  children,
}: {
  tone: "info" | "warning" | "error";
  title: string;
  description: string;
  children?: ReactNode;
}) {
  return (
    <section className={`state-panel state-${tone}`} role={tone === "error" ? "alert" : "status"}>
      <h1 className="state-title">{title}</h1>
      <p>{description}</p>
      {children ? <div className="state-actions">{children}</div> : null}
    </section>
  );
}
