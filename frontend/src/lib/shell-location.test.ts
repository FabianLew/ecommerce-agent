import { describe, expect, it } from "vitest";

import { shellLocation } from "./shell-location";
import { NAV_GROUPS } from "./store-path";

const S = "00000000-0000-4000-8000-00000000000b";

describe("navigation groups", () => {
  it("lists only the views that exist, without Customer support and Automations", () => {
    const listed = NAV_GROUPS.flatMap((group) => group.sections);
    expect(listed).toEqual(["dashboard", "decisions", "analytics", "activity", "integrations", "settings"]);
  });
});

describe("shellLocation", () => {
  it("marks a top-level section and gives it one crumb", () => {
    expect(shellLocation(`/stores/${S}/decisions`)).toEqual({
      section: "decisions",
      crumbs: [{ kind: "section", section: "decisions" }],
      container: "standard",
    });
  });

  it("uses the wide container for the overview, analytics and the integrations catalog", () => {
    expect(shellLocation(`/stores/${S}/dashboard`).container).toBe("wide");
    expect(shellLocation(`/stores/${S}/analytics`).container).toBe("wide");
    expect(shellLocation(`/stores/${S}/integrations`).container).toBe("wide");
  });

  it("names a detail page without showing its ID and links back to the section", () => {
    const location = shellLocation(`/stores/${S}/decisions/7f2c`);
    expect(location.container).toBe("detail");
    expect(location.crumbs).toEqual([
      { kind: "section", section: "decisions", href: `/stores/${S}/decisions` },
      { kind: "sub", page: "decision" },
    ]);
    expect(JSON.stringify(location)).not.toContain("7f2c");
  });

  it("knows the ad mappings and product pages", () => {
    expect(shellLocation(`/stores/${S}/integrations/ads`).crumbs.at(-1)).toEqual({ kind: "sub", page: "adMappings" });
    expect(shellLocation(`/stores/${S}/analytics/products/v1`).crumbs.at(-1)).toEqual({ kind: "sub", page: "product" });
  });

  it("handles store pages outside the sections", () => {
    expect(shellLocation(`/stores/${S}/onboarding`)).toEqual({
      page: "onboarding",
      crumbs: [{ kind: "page", page: "onboarding" }],
      container: "standard",
    });
  });

  it("returns no crumbs for unknown paths", () => {
    expect(shellLocation("/")).toEqual({ crumbs: [], container: "standard" });
    expect(shellLocation(`/stores/${S}/unknown`).crumbs).toEqual([]);
  });
});
