import type { components } from "@/api/schema";

/**
 * Semantic tones (plan section 2.1) and the mapping of every API status onto one. A status is always shown as icon +
 * text + colour; the text still comes from the message catalogs. `satisfies Record<…>` makes a new API status fail
 * the type check until it has a tone.
 */
export type Tone = "success" | "warning" | "danger" | "info" | "neutral";

type Schemas = components["schemas"];

export const PROPOSAL_STATUS_TONE = {
  PROPOSED: "warning",
  APPROVED: "success",
  REJECTED: "neutral",
  EXPIRED: "neutral",
  SUPERSEDED: "neutral",
} as const satisfies Record<Schemas["DecisionSummary"]["status"], Tone>;

export const CONNECTION_STATUS_TONE = {
  CONNECTED: "success",
  CHECK_REQUIRED: "warning",
  PENDING_CREDENTIALS: "warning",
  ERROR: "danger",
} as const satisfies Record<Schemas["MetaConnectionResponse"]["status"], Tone>;

export const HEALTH_STATUS_TONE = {
  HEALTHY: "success",
  DEGRADED: "warning",
  STALE: "danger",
  NOT_READY: "neutral",
} as const satisfies Record<"HEALTHY" | "DEGRADED" | "STALE" | "NOT_READY", Tone>;

export const SYNC_STATUS_TONE = {
  SUCCEEDED: "success",
  RUNNING: "info",
  INCOMPLETE: "danger",
  FAILED: "danger",
} as const satisfies Record<"RUNNING" | "SUCCEEDED" | "INCOMPLETE" | "FAILED", Tone>;

export const MAPPING_STATE_TONE = {
  VERIFIED: "success",
  UNVERIFIED: "warning",
  CREATIVE_CHANGED: "warning",
  REVOKED: "warning",
  NONE: "neutral",
} as const satisfies Record<"NONE" | "UNVERIFIED" | "VERIFIED" | "CREATIVE_CHANGED" | "REVOKED", Tone>;

export const QUALITY_TONE = {
  CONFIRMED: "success",
  ESTIMATED: "warning",
  UNAVAILABLE: "neutral",
} as const satisfies Record<Schemas["MetricDto"]["quality"], Tone>;

export const SEVERITY_TONE = {
  INFO: "info",
  WARNING: "warning",
  CRITICAL: "danger",
} as const satisfies Record<"INFO" | "WARNING" | "CRITICAL", Tone>;

/** A code the map does not know is shown as neutral rather than hidden (same rule as `label()` in decisions). */
export function toneOf(map: Readonly<Record<string, Tone>>, code: string): Tone {
  return Object.hasOwn(map, code) ? (map[code] ?? "neutral") : "neutral";
}
