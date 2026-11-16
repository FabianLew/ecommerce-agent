import type { components } from "@/api/schema";

type Integration = components["schemas"]["IntegrationResponse"];
type MetaConnection = components["schemas"]["MetaConnectionResponse"];

/** Catalog categories in their fixed order (plan 4.E). A category without a supported integration is not shown. */
export const CATEGORIES = ["COMMERCE", "ADVERTISING", "ANALYTICS", "MARKETING", "SUPPORT"] as const;
export type Category = (typeof CATEGORIES)[number];

export type Provider = "WOOCOMMERCE" | "META_ADS";

export interface CatalogEntry {
  provider: Provider;
  category: Category;
  /** Path below /integrations of the detail page. */
  slug: string;
  monogram: string;
}

/** The integrations the product supports today. A new one is added here when it ships, never as a placeholder. */
export const CATALOG: readonly CatalogEntry[] = [
  { provider: "WOOCOMMERCE", category: "COMMERCE", slug: "woocommerce", monogram: "W" },
  { provider: "META_ADS", category: "ADVERTISING", slug: "meta-ads", monogram: "M" },
];

/** The categories that have at least one supported integration, in catalog order. */
export function visibleCategories(catalog: readonly CatalogEntry[] = CATALOG): Category[] {
  return CATEGORIES.filter((category) => catalog.some((entry) => entry.category === category));
}

/** The one status a catalog tile shows. */
export type TileStatus = "CONNECTED" | "NOT_CONNECTED" | "ATTENTION";

/**
 * WooCommerce: "Connected" only when the backend reports a successful connection, the health is HEALTHY or
 * DEGRADED (working, with warnings shown on the detail page) and the last import did not fail. Anything else that
 * exists needs attention.
 */
export function wooTileStatus(integration: Integration | undefined): TileStatus {
  if (!integration) {
    return "NOT_CONNECTED";
  }
  const syncFailed = integration.lastSync?.status === "FAILED" || integration.lastSync?.status === "INCOMPLETE";
  const working = integration.health.status === "HEALTHY" || integration.health.status === "DEGRADED";
  return integration.status === "CONNECTED" && working && !syncFailed ? "CONNECTED" : "ATTENTION";
}

/** Meta Ads: "Connected" only for a connected account whose last ad read had no error. */
export function metaTileStatus(connection: MetaConnection | undefined): TileStatus {
  if (!connection) {
    return "NOT_CONNECTED";
  }
  return connection.status === "CONNECTED" && !connection.lastSyncError ? "CONNECTED" : "ATTENTION";
}
