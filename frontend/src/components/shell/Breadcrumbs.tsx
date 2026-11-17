"use client";

import { ChevronRight } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";

import { useMessages } from "@/i18n/LocaleProvider";
import { type Crumb, shellLocation } from "@/lib/shell-location";

import { Icon } from "../ui/Icon";

/** Where the user is (04 §2). Resource IDs are never shown; a detail page names its kind instead. */
export function Breadcrumbs() {
  const pathname = usePathname();
  const t = useMessages();
  const { crumbs } = shellLocation(pathname);
  if (crumbs.length === 0) return <span />;

  const label = (crumb: Crumb) =>
    crumb.kind === "section"
      ? t.sections[crumb.section]
      : crumb.kind === "page"
        ? t.shell.pages[crumb.page]
        : t.shell.subPages[crumb.page];

  return (
    <nav className="breadcrumbs" aria-label={t.shell.breadcrumbs}>
      <ol>
        {crumbs.map((crumb, index) => {
          const last = index === crumbs.length - 1;
          return (
            <li key={index}>
              {crumb.kind === "section" && crumb.href && !last ? (
                <Link href={crumb.href}>{label(crumb)}</Link>
              ) : (
                <span aria-current={last ? "page" : undefined}>{label(crumb)}</span>
              )}
              {last ? null : <Icon icon={ChevronRight} size="sm" />}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
