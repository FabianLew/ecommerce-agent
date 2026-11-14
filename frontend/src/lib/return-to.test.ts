import { describe, expect, it } from "vitest";

import { safeReturnTo } from "./return-to";

describe("safeReturnTo", () => {
  it("keeps same-origin paths with query", () => {
    expect(safeReturnTo("/stores/a/analytics?from=2026-11-01")).toBe("/stores/a/analytics?from=2026-11-01");
  });

  it.each([
    null,
    "",
    "https://evil.example/",
    "//evil.example/",
    "/\\evil.example",
    "javascript:alert(1)",
    "/auth/login?returnTo=/",
    "/stores/a\n/x",
  ])("falls back to / for %j", (value) => {
    expect(safeReturnTo(value)).toBe("/");
  });
});
