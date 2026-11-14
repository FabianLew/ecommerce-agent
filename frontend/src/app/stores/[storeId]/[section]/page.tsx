import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { backend } from "@/lib/api";
import { isSection, SECTIONS, storePath } from "@/lib/store-path";

export const dynamic = "force-dynamic";

const ROLE_LABELS: Record<string, string> = {
  OWNER: "Właściciel",
  DECISION_OPERATOR: "Operator decyzji",
  SUPPORT_OPERATOR: "Operator obsługi klienta",
  ANALYST: "Analityk",
};

const PERMISSION_LABELS: Record<string, string> = {
  STORE_READ: "Odczyt danych sklepu",
  STORE_MANAGE: "Zarządzanie sklepem",
};

export default async function SectionPage({ params }: { params: Promise<{ storeId: string; section: string }> }) {
  const { storeId, section } = await params;
  if (!isSection(section)) {
    notFound();
  }
  const label = SECTIONS.find((item) => item.slug === section)?.label ?? section;

  if (section !== "dashboard") {
    return (
      <>
        <PageHeading>{label}</PageHeading>
        <StatePanel
          tone="info"
          title="Ten widok jeszcze nie jest dostępny"
          description="Powstanie w kolejnych iteracjach. Nawigacja i wybór sklepu już działają."
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
        <h2 id="store-summary">Sklep</h2>
        <dl className="details">
          <dt>Nazwa</dt>
          <dd data-testid="store-name">{store.name}</dd>
          <dt>Organizacja</dt>
          <dd>{store.organizationName}</dd>
          <dt>Waluta</dt>
          <dd>{store.currency}</dd>
          <dt>Strefa czasu</dt>
          <dd>{store.timezone}</dd>
          <dt>Twoje role</dt>
          <dd>{store.roles.map((role) => ROLE_LABELS[role] ?? role).join(", ")}</dd>
          <dt>Uprawnienia</dt>
          <dd>{store.permissions.map((permission) => PERMISSION_LABELS[permission] ?? permission).join(", ")}</dd>
        </dl>
      </section>
      <StatePanel
        tone="info"
        title="Monitorowanie sklepu nie jest jeszcze uruchomione"
        description="Pierwsze dane pojawią się po połączeniu WooCommerce (kolejna iteracja)."
      />
    </>
  );
}
