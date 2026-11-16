"use client";

import { StandaloneState, StatePanel } from "@/components/StatePanel";
import { PRODUCT_NAME } from "@/lib/brand";
import { useMessages } from "@/i18n/LocaleProvider";

export default function ErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const t = useMessages();
  return (
    <StandaloneState productName={PRODUCT_NAME}>
      <StatePanel tone="error" title={t.errorPage.title} description={t.errorPage.description(error.digest ?? t.common.none)}
        level={1}>
        <button type="button" className="button primary" onClick={reset}>{t.common.tryAgain}</button>
      </StatePanel>
    </StandaloneState>
  );
}
