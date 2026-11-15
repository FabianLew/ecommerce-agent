import { describe, expect, it } from "vitest";

import { formatMoney } from "./format";

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
