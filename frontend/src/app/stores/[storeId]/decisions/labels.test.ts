import { describe, expect, it } from "vitest";

import { en } from "@/i18n/en";
import { pl } from "@/i18n/pl";

import { coverText, label, problemTitle, recommendationText } from "./labels";

const decision = {
  subject: { type: "PRODUCT_VARIANT", id: "v-1", sku: "SERUM-30", productName: "Serum", variantName: "Serum 30 ml" },
  action: {
    type: "PAUSE_AD",
    targetType: "META_AD",
    targetId: "a-1",
    targetExternalId: "ad-9001",
    targetName: "Serum dedicated",
    desiredStatus: "PAUSED",
  },
  disposition: "RECOMMEND_ACTION" as const,
  facts: { daysOfCover: 2.5714, thresholdDays: 3, replenishmentKnown: false },
};

describe("decision labels", () => {
  it("names the problem, the action and the cover in both languages", () => {
    expect(problemTitle(decision, en.decisions)).toBe("Low stock: Serum 30 ml (SERUM-30)");
    expect(recommendationText(decision, en.decisions)).toBe("Pause the ad “Serum dedicated”");
    expect(coverText(decision, en, "en")).toBe("2.6 days");
    expect(coverText(decision, pl, "pl")).toBe("2,6 dnia");
  });

  it("falls back to the disposition and to unknown cover", () => {
    const none = { ...decision, action: undefined, disposition: "MONITOR" as const, facts: undefined };
    expect(recommendationText(none, en.decisions)).toBe("Keep monitoring");
    expect(coverText(none, en, "en")).toBe("unknown");
  });

  it("shows unknown codes as they are, also for prototype keys", () => {
    expect(label(en.decisions.reasonCodes, "NEW_CODE")).toBe("NEW_CODE");
    expect(label(en.decisions.reasonCodes, "constructor")).toBe("constructor");
  });
});
