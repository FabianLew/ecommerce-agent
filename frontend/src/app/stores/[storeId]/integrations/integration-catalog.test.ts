import { describe, expect, it } from "vitest";

import type { components } from "@/api/schema";

import { CATALOG, metaTileStatus, visibleCategories, wooTileStatus } from "./integration-catalog";

type Integration = components["schemas"]["IntegrationResponse"];
type MetaConnection = components["schemas"]["MetaConnectionResponse"];

function woo(overrides: Partial<Integration> = {}, health: Integration["health"]["status"] = "HEALTHY"): Integration {
  return {
    id: "i",
    provider: "WOOCOMMERCE",
    status: "CONNECTED",
    storeUrl: "https://shop.example",
    allowedActions: [],
    warnings: [],
    health: { status: health, reasons: [], missedWebhooks24h: 0 },
    history: { status: "COMPLETE" },
    webhook: { secretConfigured: false },
    ...overrides,
  } as Integration;
}

function meta(overrides: Partial<MetaConnection> = {}): MetaConnection {
  return { id: "m", accountId: "1", adsSeen: 0, allowedActions: [], canWrite: false, status: "CONNECTED", ...overrides } as MetaConnection;
}

describe("integration catalog", () => {
  it("shows only categories with a supported integration, in the fixed order", () => {
    expect(visibleCategories()).toEqual(["COMMERCE", "ADVERTISING"]);
    expect(visibleCategories([...CATALOG, { provider: "WOOCOMMERCE", category: "SUPPORT", slug: "x", monogram: "X" }]))
      .toEqual(["COMMERCE", "ADVERTISING", "SUPPORT"]);
  });

  it("maps WooCommerce to connected only for a working connection", () => {
    expect(wooTileStatus(undefined)).toBe("NOT_CONNECTED");
    expect(wooTileStatus(woo())).toBe("CONNECTED");
    expect(wooTileStatus(woo({}, "DEGRADED"))).toBe("CONNECTED");
    expect(wooTileStatus(woo({}, "STALE"))).toBe("ATTENTION");
    expect(wooTileStatus(woo({}, "NOT_READY"))).toBe("ATTENTION");
    expect(wooTileStatus(woo({ status: "ERROR" }))).toBe("ATTENTION");
    expect(wooTileStatus(woo({ status: "CHECK_REQUIRED" }))).toBe("ATTENTION");
    expect(wooTileStatus(woo({ status: "PENDING_CREDENTIALS" }))).toBe("ATTENTION");
    expect(wooTileStatus(woo({ lastSync: { status: "FAILED" } as Integration["lastSync"] }))).toBe("ATTENTION");
  });

  it("maps Meta Ads to connected only without a read error", () => {
    expect(metaTileStatus(undefined)).toBe("NOT_CONNECTED");
    expect(metaTileStatus(meta())).toBe("CONNECTED");
    expect(metaTileStatus(meta({ lastSyncError: "RATE_LIMIT" }))).toBe("ATTENTION");
    expect(metaTileStatus(meta({ status: "ERROR" }))).toBe("ATTENTION");
    expect(metaTileStatus(meta({ status: "PENDING_CREDENTIALS" }))).toBe("ATTENTION");
  });
});
