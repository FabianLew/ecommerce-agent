import { describe, expect, it } from "vitest";

import { formatCount, formatDays, formatMoney, formatRelative, plural } from "./format";

describe("formatMoney", () => {
  it("formats each amount in its own currency, never assuming PLN", () => {
    expect(formatMoney("12.5", "USD", "en")).toBe("US$12.50");
    expect(formatMoney("12.5", "EUR", "en")).toBe("€12.50");
    expect(formatMoney("12.5", "PLN", "pl")).toMatch(/^12,50\s?zł$/);
    expect(formatMoney("12.5", "USD", "pl")).toMatch(/^12,50\s?USD$/);
  });

  it("keeps small AI costs and exact decimals", () => {
    expect(formatMoney("0.04500000", "USD", "en")).toBe("US$0.045");
    expect(formatMoney("0.13500000", "USD", "en")).toBe("US$0.135");
    expect(formatMoney("0.00000123", "USD", "en")).toBe("US$0.000001");
    expect(formatMoney("1234567.10", "EUR", "en")).toBe("€1,234,567.10");
  });

  it("shows a dash for a missing or malformed amount or currency", () => {
    expect(formatMoney(null, "USD", "en")).toBe("—");
    expect(formatMoney("1.00", null, "en")).toBe("—");
    expect(formatMoney("1e3", "USD", "en")).toBe("—");
  });
});

describe("formatDays", () => {
  const en = { one: "day", other: "days" };
  const pl = { one: "dzień", few: "dni", many: "dni", other: "dnia" };

  it("never prints raw API decimals", () => {
    expect(formatDays("2.4285714", "en", en)).toBe("2.4 days");
    expect(formatDays(2.6, "pl", pl)).toBe("2,6 dnia");
  });

  it("uses the word form of the number that is shown", () => {
    expect(formatDays(1, "en", en)).toBe("1 day");
    expect(formatDays(1.04, "en", en)).toBe("1 day");
    expect(formatDays(1, "pl", pl)).toBe("1 dzień");
    expect(formatDays(3, "pl", pl)).toBe("3 dni");
    expect(formatDays(5, "pl", pl)).toBe("5 dni");
    expect(formatDays(1.5, "pl", pl)).toBe("1,5 dnia");
  });

  it("shows a dash for an unknown value", () => {
    expect(formatDays(undefined, "en", en)).toBe("—");
    expect(formatDays("n/a", "en", en)).toBe("—");
  });
});

describe("plural and formatCount", () => {
  it("picks Polish one/few/many", () => {
    const forms = { one: "decyzja", few: "decyzje", many: "decyzji", other: "decyzji" };
    expect([1, 2, 5, 22, 25].map((n) => plural("pl", n, forms))).toEqual([
      "decyzja", "decyzje", "decyzji", "decyzje", "decyzji",
    ]);
  });

  it("rounds counts", () => {
    expect(formatCount("1234.6", "en")).toBe("1,235");
    expect(formatCount(null, "en")).toBe("—");
  });
});

describe("formatRelative", () => {
  const now = new Date("2026-11-16T12:00:00Z");
  it("uses minutes, hours and days", () => {
    expect(formatRelative("2026-11-16T12:30:00Z", "en", now)).toBe("in 30 minutes");
    expect(formatRelative("2026-11-17T11:00:00Z", "en", now)).toBe("in 23 hours");
    expect(formatRelative("2026-11-19T12:00:00Z", "en", now)).toBe("in 3 days");
    expect(formatRelative("2026-11-16T10:00:00Z", "en", now)).toBe("2 hours ago");
    expect(formatRelative("2026-11-17T11:00:00Z", "pl", now)).toBe("za 23 godziny");
  });
});
