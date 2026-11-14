"use client";

import { useEffect, useRef } from "react";

let renderedOnce = false;

/** Page title that receives focus after client-side navigation (04 §14), not on the first load. */
export function PageHeading({ children }: { children: string }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    if (renderedOnce) {
      ref.current?.focus();
    }
    renderedOnce = true;
  }, [children]);
  return (
    <h1 ref={ref} tabIndex={-1} className="page-title">
      {children}
    </h1>
  );
}
