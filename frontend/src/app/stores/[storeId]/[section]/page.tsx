import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { getMessages } from "@/i18n/server";
import { backend } from "@/lib/api";
import { isSection, storePath } from "@/lib/store-path";

export const dynamic = "force-dynamic";

export default async function SectionPage({ params }: { params: Promise<{ storeId: string; section: string }> }) {
  const { storeId, section } = await params;
  if (!isSection(section)) {
    notFound();
  }
  const t = await getMessages();
  const label = t.sections[section];

  if (section !== "dashboard") {
    return (
      <>
        <PageHeading>{label}</PageHeading>
        <StatePanel
          tone="info"
          title={t.store.viewNotReadyTitle}
          description={t.store.viewNotReadyDescription}
        />
      </>
    );
  }

  const api = await backend(storePath(storeId));
  const { data: store } = await api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } });
  if (!store) {
    notFound();
  }
  return (
    <>
      <PageHeading>{label}</PageHeading>
      <section className="card" aria-labelledby="store-summary">
        <h2 id="store-summary">{t.store.summary}</h2>
        <dl className="details">
          <dt>{t.store.name}</dt>
          <dd data-testid="store-name">{store.name}</dd>
          <dt>{t.store.organization}</dt>
          <dd>{store.organizationName}</dd>
          <dt>{t.store.currency}</dt>
          <dd>{store.currency}</dd>
          <dt>{t.store.timezone}</dt>
          <dd>{store.timezone}</dd>
          <dt>{t.store.roles}</dt>
          <dd>{store.roles.map((role) => t.store.roleLabels[role] ?? role).join(", ")}</dd>
          <dt>{t.store.permissions}</dt>
          <dd>{store.permissions.map((permission) => t.store.permissionLabels[permission] ?? permission).join(", ")}</dd>
        </dl>
      </section>
      <StatePanel
        tone="info"
        title={t.store.monitoringOffTitle}
        description={t.store.monitoringOffDescription}
      />
    </>
  );
}
