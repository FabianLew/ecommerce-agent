/** Sidebar sections from document 04 §2, in display order. */
export const SECTIONS = [
  { slug: "dashboard", label: "Przegląd" },
  { slug: "decisions", label: "Decyzje" },
  { slug: "support", label: "Obsługa klientów" },
  { slug: "analytics", label: "Analityka" },
  { slug: "automations", label: "Automatyzacje" },
  { slug: "activity", label: "Aktywność" },
  { slug: "integrations", label: "Integracje" },
  { slug: "settings", label: "Ustawienia" },
] as const;

export type SectionSlug = (typeof SECTIONS)[number]["slug"];

export function isSection(value: string): value is SectionSlug {
  return SECTIONS.some((section) => section.slug === value);
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
