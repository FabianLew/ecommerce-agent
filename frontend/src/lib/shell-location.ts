import { isSection, type SectionSlug, storePath } from "./store-path";

/** Width of the content column for a page (plan section 2.3). */
export type ContainerWidth = "standard" | "detail" | "wide";

/** A sub-page below a section, named in the breadcrumbs. */
export type SubPage = "decision" | "event" | "product" | "adMappings";

/** A page outside the eight sections that still lives under the store. */
export type StorePage = "onboarding" | "notifications";

export type Crumb =
  | { kind: "section"; section: SectionSlug; href?: string }
  | { kind: "page"; page: StorePage }
  | { kind: "sub"; page: SubPage };

export interface ShellLocation {
  /** The section to mark as current in the navigation, if any. */
  section?: SectionSlug;
  page?: StorePage;
  crumbs: Crumb[];
  container: ContainerWidth;
}

const WIDE: readonly SectionSlug[] = ["dashboard", "analytics", "integrations"];

/**
 * Where a store path sits in the shell: the current section, the breadcrumb trail and the content width. Only the
 * shape of the path is used; a resource ID is never shown in the breadcrumbs.
 */
export function shellLocation(pathname: string): ShellLocation {
  const [root, storeId, first, ...rest] = pathname.split("/").filter(Boolean);
  if (root !== "stores" || !storeId || !first) {
    return { crumbs: [], container: "standard" };
  }
  const store = decodeURIComponent(storeId);
  if (first === "onboarding" || first === "notifications") {
    return { page: first, crumbs: [{ kind: "page", page: first }], container: "standard" };
  }
  if (!isSection(first)) {
    return { crumbs: [], container: "standard" };
  }
  if (rest.length === 0) {
    return {
      section: first,
      crumbs: [{ kind: "section", section: first }],
      container: WIDE.includes(first) ? "wide" : "standard",
    };
  }
  const parent: Crumb = { kind: "section", section: first, href: storePath(store, first) };
  const sub = subPage(first, rest);
  return {
    section: first,
    crumbs: sub ? [parent, { kind: "sub", page: sub }] : [parent],
    container: first === "decisions" || first === "activity" ? "detail" : "standard",
  };
}

function subPage(section: SectionSlug, rest: string[]): SubPage | undefined {
  if (section === "decisions") return "decision";
  if (section === "activity") return "event";
  if (section === "analytics" && rest[0] === "products") return "product";
  if (section === "integrations" && rest[0] === "ads") return "adMappings";
  return undefined;
}
