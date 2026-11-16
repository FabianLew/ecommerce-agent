import Link from "next/link";

import { StandaloneState, StatePanel } from "@/components/StatePanel";
import { PRODUCT_NAME } from "@/lib/brand";
import { LanguageSwitcher } from "@/i18n/LanguageSwitcher";
import { getMessages } from "@/i18n/server";

export const dynamic = "force-dynamic";

export default async function SignedOut({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const [{ error }, t] = await Promise.all([searchParams, getMessages()]);
  const message = error && Object.hasOwn(t.signedOut.errors, error) ? t.signedOut.errors[error] : undefined;
  return (
    <StandaloneState productName={PRODUCT_NAME}>
      <StatePanel
        level={1}
        tone={message ? "error" : "info"}
        title={message ? t.signedOut.failedTitle : t.signedOut.signedOutTitle}
        description={message ?? t.signedOut.signedOutDescription}
      >
        <Link href="/auth/login" className="button primary" prefetch={false}>{t.common.signIn}</Link>
        <LanguageSwitcher />
      </StatePanel>
    </StandaloneState>
  );
}
