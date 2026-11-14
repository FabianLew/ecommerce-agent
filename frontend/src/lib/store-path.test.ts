import { describe, expect, it } from "vitest";

import { isSection, SECTIONS, switchStorePath } from "./store-path";

const B = "20000000-0000-4000-8000-00000000000b";

describe("switchStorePath", () => {
  it("keeps the section", () => {
    expect(switchStorePath("/stores/a/activity", B)).toBe(`/stores/${B}/activity`);
  });

  it("drops resource IDs of the previous store", () => {
    expect(switchStorePath("/stores/a/decisions/proposal-123", B)).toBe(`/stores/${B}/decisions`);
    expect(switchStorePath("/stores/a/support/conversation-9", B)).toBe(`/stores/${B}/support`);
  });

  it("falls back to the dashboard outside a known section", () => {
    expect(switchStorePath("/", B)).toBe(`/stores/${B}/dashboard`);
    expect(switchStorePath("/stores/a/unknown", B)).toBe(`/stores/${B}/dashboard`);
  });

  it("encodes the store ID", () => {
    expect(switchStorePath("/stores/a/dashboard", "../x")).toBe("/stores/..%2Fx/dashboard");
  });
});

describe("sections", () => {
  it("has the eight views from document 04 §2", () => {
    expect(SECTIONS.map((section) => section.slug)).toEqual([
      "dashboard", "decisions", "support", "analytics", "automations", "activity", "integrations", "settings",
    ]);
    expect(isSection("onboarding")).toBe(false);
  });
});
