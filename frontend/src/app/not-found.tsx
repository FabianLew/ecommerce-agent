import Link from "next/link";

import { StatePanel } from "@/components/StatePanel";
import { getMessages } from "@/i18n/server";

export default async function NotFound() {
  const t = await getMessages();
  return (
    <main className="centered">
      <StatePanel tone="warning" title={t.notFound.title} description={t.notFound.description}>
        <Link href="/" className="button primary">{t.common.goToStores}</Link>
      </StatePanel>
    </main>
  );
}
