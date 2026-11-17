"use client";

import { StatePanel } from "@/components/StatePanel";
import { useMessages } from "@/i18n/LocaleProvider";

/**
 * An error in a store page keeps the shell, so the navigation and the store switcher still work (plan section 4.G).
 * The error ID lets support find the server log entry.
 */
export default function StorePageError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const t = useMessages();
  return (
    <StatePanel
      tone="error"
      title={t.shell.pageError.title}
      description={t.shell.pageError.description(error.digest ?? t.common.none)}
    >
      <button type="button" className="button primary" onClick={reset}>{t.common.tryAgain}</button>
    </StatePanel>
  );
}
