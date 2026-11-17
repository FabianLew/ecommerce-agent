import Link from "next/link";
import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatusBadge } from "@/components/ui/StatusBadge";
import type { Tone } from "@/components/ui/tones";
import { formatInstant } from "@/i18n/format";
import { getLocale, getMessages } from "@/i18n/server";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

import { CATALOG, type CatalogEntry, metaTileStatus, type TileStatus, visibleCategories, wooTileStatus } from "./integration-catalog";

export const dynamic = "force-dynamic";

const TILE_TONE: Record<TileStatus, Tone> = { CONNECTED: "success", NOT_CONNECTED: "neutral", ATTENTION: "warning" };

/**
 * Integrations catalog (plan 4.E): the supported integrations grouped by category, each with one status mapped from
 * the backend state. Categories without a supported integration are not shown (D-4).
 */
export default async function IntegrationsPage({ params }: { params: Promise<{ storeId: string }> }) {
  const { storeId } = await params;
  const path = storePath(storeId, "integrations");
  const api = await backend(path);
  const [store, integrations, meta, locale, messages] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/integrations", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/meta-connection", { params: { path: { storeId } } }),
    getLocale(),
    getMessages(),
  ]);
  const t = messages.integrations;
  redirectIfUnauthenticated(integrations.response.status, path);
  if (!store.data || !integrations.data) {
    notFound();
  }
  const zone = store.data.timezone;
  const woo = integrations.data.items.find((item) => item.provider === "WOOCOMMERCE");
  const connection = meta.data?.connection;

  function tile(entry: CatalogEntry): { status: TileStatus; detail?: string } {
    if (entry.provider === "WOOCOMMERCE") {
      const status = wooTileStatus(woo);
      if (!woo) return { status };
      if (status === "CONNECTED") {
        const until = woo.health.ordersCompleteUntil;
        return { status, detail: until ? t.dataUntil(formatInstant(until, zone, locale)) : undefined };
      }
      return {
        status,
        detail: woo.status === "CONNECTED" ? t.healthStatus[woo.health.status] : t.status[woo.status],
      };
    }
    const status = metaTileStatus(connection);
    if (!connection) return { status };
    if (status === "CONNECTED") {
      return { status, detail: connection.lastSyncAt ? t.lastAdRead(formatInstant(connection.lastSyncAt, zone, locale)) : undefined };
    }
    return { status, detail: messages.meta.status[connection.status] };
  }

  return (
    <>
      <header className="page-header">
        <PageHeading>{messages.sections.integrations}</PageHeading>
        <p className="page-sub">{t.catalogIntro}</p>
      </header>
      {visibleCategories().map((category) => (
        <section key={category} className="catalog-section" aria-labelledby={`category-${category}`}>
          <h2 id={`category-${category}`} className="catalog-heading">{t.categories[category]}</h2>
          <ul className="catalog-grid">
            {CATALOG.filter((entry) => entry.category === category).map((entry) => {
              const { status, detail } = tile(entry);
              const provider = t.providers[entry.provider];
              return (
                <li key={entry.provider} className="card integration-tile" data-testid={`tile-${entry.slug}`}>
                  <span className="monogram" aria-hidden="true">{entry.monogram}</span>
                  <div className="tile-body">
                    <h3 className="tile-name">
                      <Link href={`${path}/${entry.slug}`} className="tile-link">{provider.name}</Link>
                    </h3>
                    <p className="tile-description">{provider.description}</p>
                  </div>
                  <div className="tile-footer">
                    <StatusBadge tone={TILE_TONE[status]} data-testid="tile-status">{t.tileStatus[status]}</StatusBadge>
                    {detail ? <span className="tile-detail">{detail}</span> : null}
                    <span className="tile-action" aria-hidden="true">
                      {status === "NOT_CONNECTED" ? t.connect : t.manage}
                    </span>
                  </div>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </>
  );
}
