import { describe, expect, it } from "vitest";

import {
  CONNECTION_STATUS_TONE,
  HEALTH_STATUS_TONE,
  MAPPING_STATE_TONE,
  PROPOSAL_STATUS_TONE,
  SEVERITY_TONE,
  SYNC_STATUS_TONE,
  toneOf,
} from "./tones";

describe("toneOf", () => {
  it("returns the mapped tone", () => {
    expect(toneOf(PROPOSAL_STATUS_TONE, "APPROVED")).toBe("success");
    expect(toneOf(SEVERITY_TONE, "CRITICAL")).toBe("danger");
  });

  it("shows an unknown code as neutral instead of hiding it", () => {
    expect(toneOf(PROPOSAL_STATUS_TONE, "SOMETHING_NEW")).toBe("neutral");
  });

  it("does not read inherited properties", () => {
    expect(toneOf(PROPOSAL_STATUS_TONE, "toString")).toBe("neutral");
    expect(toneOf(PROPOSAL_STATUS_TONE, "__proto__")).toBe("neutral");
  });
});

describe("tone maps", () => {
  // A failure or a state that needs attention must never look like success (the redesign must not soften warnings).
  it.each([
    [CONNECTION_STATUS_TONE, ["ERROR", "CHECK_REQUIRED", "PENDING_CREDENTIALS"]],
    [HEALTH_STATUS_TONE, ["DEGRADED", "STALE"]],
    [SYNC_STATUS_TONE, ["FAILED", "INCOMPLETE"]],
    [MAPPING_STATE_TONE, ["UNVERIFIED", "CREATIVE_CHANGED", "REVOKED"]],
    [SEVERITY_TONE, ["WARNING", "CRITICAL"]],
  ] as const)("keeps problem states in warning or danger", (map, codes) => {
    for (const code of codes) {
      expect(["warning", "danger"]).toContain(toneOf(map, code));
    }
  });

  it("marks a proposal waiting for approval as needing attention", () => {
    expect(toneOf(PROPOSAL_STATUS_TONE, "PROPOSED")).toBe("warning");
  });
});
