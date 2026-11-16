import { describe, expect, it } from "vitest";

import type { components } from "@/api/schema";
import { en } from "@/i18n/en";
import { pl } from "@/i18n/pl";

import { evidenceValue } from "./evidence-value";

type EvidenceItem = components["schemas"]["EvidenceItem"];

function item(type: EvidenceItem["type"], content: Record<string, unknown>): EvidenceItem {
  return {
    id: "e1", type, content, kind: "OBSERVATION", citedByProposal: true, contentHash: "h", inputEvidenceIds: [],
    observedAt: "2026-11-16T12:43:00Z", availableAt: "2026-11-16T12:43:00Z", computedAt: "2026-11-16T12:43:00Z",
    resourceRef: "VARIANT:1", version: 1,
  } as EvidenceItem;
}

describe("evidenceValue", () => {
  it("reads stock and metrics in business terms, without raw decimals", () => {
    expect(evidenceValue(item("STOCK_READING", { availableToSell: 18, stockQuantity: 20 }), en, "en"))
      .toBe("18 available to sell · 20 in stock");
    expect(evidenceValue(item("UNITS_SOLD", { value: "49" }), en, "en")).toBe("49 units");
    expect(evidenceValue(item("SALES_VELOCITY", { value: "7.0000" }), en, "en")).toBe("7 per day");
    expect(evidenceValue(item("DAYS_OF_COVER", { value: "2.5714" }), en, "en")).toBe("2.6 days");
    expect(evidenceValue(item("DAYS_OF_COVER", { value: "2.5714" }), pl, "pl")).toBe("2,6 dnia");
  });

  it("says when a metric has no value", () => {
    expect(evidenceValue(item("DAYS_OF_COVER", { value: null, unavailableReason: "NO_SALES" }), en, "en"))
      .toBe("Not available");
  });

  it("reads ads and mappings", () => {
    expect(evidenceValue(item("AD_STATE", { name: "Serum ad", ownStatus: "ACTIVE" }), en, "en")).toBe("Serum ad · running");
    expect(evidenceValue(item("AD_MAPPING", { scope: "SINGLE_VARIANT", verification: "VERIFIED" }), en, "en"))
      .toBe("Promotes only this product · confirmed");
    expect(evidenceValue(item("REPLENISHMENT", { known: false, source: "NONE" }), en, "en")).toBe("No restock date known");
  });

  it("does not guess for unknown content", () => {
    expect(evidenceValue(item("STOCK_READING", {}), en, "en")).toBeUndefined();
    expect(evidenceValue(item("AD_STATE", { name: "x" }), en, "en")).toBeUndefined();
  });
});
