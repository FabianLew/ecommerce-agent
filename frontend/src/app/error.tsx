"use client";

import { StatePanel } from "@/components/StatePanel";
import { useMessages } from "@/i18n/LocaleProvider";

export default function ErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const t = useMessages();
  return (
    <main className="centered">
      <StatePanel tone="error" title={t.errorPage.title} description={t.errorPage.description(error.digest ?? t.common.none)}>
        <button type="button" className="button primary" onClick={reset}>{t.common.tryAgain}</button>
      </StatePanel>
    </main>
  );
}
