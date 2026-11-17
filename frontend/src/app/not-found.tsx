import Link from "next/link";

import { StandaloneState, StatePanel } from "@/components/StatePanel";
import { PRODUCT_NAME } from "@/lib/brand";
import { getMessages } from "@/i18n/server";

export default async function NotFound() {
  const t = await getMessages();
  return (
    <StandaloneState productName={PRODUCT_NAME}>
      <StatePanel tone="warning" title={t.notFound.title} description={t.notFound.description} level={1}>
        <Link href="/" className="button primary">{t.common.goToStores}</Link>
      </StatePanel>
    </StandaloneState>
  );
}
