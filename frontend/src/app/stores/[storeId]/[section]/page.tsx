import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { getMessages } from "@/i18n/server";
import { isSection } from "@/lib/store-path";

export const dynamic = "force-dynamic";

export default async function SectionPage({ params }: { params: Promise<{ storeId: string; section: string }> }) {
  const { section } = await params;
  if (!isSection(section)) {
    notFound();
  }
  const t = await getMessages();
  const label = t.sections[section];

  return (
    <>
      <PageHeading>{label}</PageHeading>
      <StatePanel tone="info" title={t.store.viewNotReadyTitle} description={t.store.viewNotReadyDescription} />
    </>
  );
}
