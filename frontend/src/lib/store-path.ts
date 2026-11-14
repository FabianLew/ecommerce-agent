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
