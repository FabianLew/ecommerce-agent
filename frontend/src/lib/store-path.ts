/** Sidebar sections from document 04 §2, in display order. Their labels live in the message catalogs. */
export const SECTIONS = [
  "dashboard",
  "decisions",
  "support",
  "analytics",
  "automations",
  "activity",
  "integrations",
  "settings",
] as const;

export type SectionSlug = (typeof SECTIONS)[number];

/**
 * Sidebar groups (plan section 2.6). Only views that exist are listed: Customer support and Automations stay routes
 * with an honest "not in this version" state, so old links keep working, but they are not offered in the navigation.
 */
export const NAV_GROUPS = [
  { id: "main", sections: ["dashboard", "decisions"] },
  { id: "insights", sections: ["analytics", "activity"] },
  { id: "setup", sections: ["integrations", "settings"] },
] as const satisfies readonly { id: string; sections: readonly SectionSlug[] }[];

export type NavGroupId = (typeof NAV_GROUPS)[number]["id"];

export function isSection(value: string): value is SectionSlug {
  return (SECTIONS as readonly string[]).includes(value);
}

export function storePath(storeId: string, section: SectionSlug = "dashboard"): string {
  return `/stores/${encodeURIComponent(storeId)}/${section}`;
}

/**
 * Target of a store switch (04 §2): keep the current section, drop resource IDs and query parameters
 * of the previous store. A link to another store's resource must never be carried over.
 */
export function switchStorePath(currentPathname: string, targetStoreId: string): string {
  const segments = currentPathname.split("/").filter(Boolean);
  const section = segments[0] === "stores" ? segments[2] : undefined;
  return storePath(targetStoreId, section && isSection(section) ? section : "dashboard");
}

/** Decision Detail of one proposal revision (04 §2: `/decisions/:proposalId`). */
export function decisionPath(storeId: string, proposalId: string): string {
  return `${storePath(storeId, "decisions")}/${encodeURIComponent(proposalId)}`;
}
