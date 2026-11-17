import { describe, expect, it } from "vitest";

import { en } from "./en";
import { DEFAULT_LOCALE, resolveLocale } from "./locale";
import { messagesFor } from "./messages";
import { pl } from "./pl";

function shape(value: unknown, path = ""): string[] {
  // Plural word forms differ per language by design (Polish has one/few/many), so only their presence is compared.
  if (path.endsWith("Forms")) {
    return [`${path}:forms`];
  }
  if (value && typeof value === "object") {
    return Object.entries(value).flatMap(([key, child]) => shape(child, `${path}.${key}`));
  }
  return [`${path}:${typeof value}`];
}

describe("locale", () => {
  it("defaults to English when there is no valid choice", () => {
    expect(DEFAULT_LOCALE).toBe("en");
    expect(resolveLocale(undefined)).toBe("en");
    expect(resolveLocale("de")).toBe("en");
    expect(resolveLocale("pl")).toBe("pl");
  });
});

describe("catalogs", () => {
  it("have the same keys and value kinds in every language", () => {
    expect(shape(pl).sort()).toEqual(shape(en).sort());
  });

  it("have no empty texts", () => {
    for (const catalog of [en, pl]) {
      const strings = shape(catalog).filter((entry) => entry.endsWith(":string"));
      expect(strings.length).toBeGreaterThan(50);
      expect(JSON.stringify(catalog)).not.toContain('""');
    }
  });

  it("interpolate values in both languages", () => {
    expect(messagesFor("en").integrations.saveFailed("req-1")).toContain("req-1");
    expect(messagesFor("pl").integrations.saveFailed("req-1")).toContain("req-1");
  });
});
