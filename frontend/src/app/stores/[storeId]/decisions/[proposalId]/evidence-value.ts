import type { components } from "@/api/schema";
import { formatCount, formatDays, formatNumber, toNumber } from "@/i18n/format";
import type { Locale } from "@/i18n/locale";
import type { Messages } from "@/i18n/messages";

import { label } from "../labels";

type EvidenceItem = components["schemas"]["EvidenceItem"];
type Content = Record<string, unknown>;

/**
 * One readable line per evidence item (plan 4.C point 5): the value an owner cares about, from the evidence content
 * of known types. An unknown type, or content without the expected fields, returns `undefined` so the page points
 * to the technical details instead of guessing.
 */
export function evidenceValue(item: EvidenceItem, messages: Messages, locale: Locale): string | undefined {
  const c = (item.content ?? {}) as Content;
  const t = messages.decisions.evidenceValue;
  const text = (key: string) => (typeof c[key] === "string" ? (c[key] as string) : undefined);
  const metric = () => (c.value === null || c.value === undefined ? undefined : toNumber(c.value as string));

  switch (item.type) {
    case "STOCK_READING": {
      const available = toNumber(c.availableToSell as number);
      const total = toNumber(c.stockQuantity as number);
      if (available === undefined || total === undefined) return undefined;
      return t.stock(formatCount(available, locale), formatCount(total, locale));
    }
    case "UNITS_SOLD": {
      const value = metric();
      return value === undefined ? t.unavailable : t.units(formatCount(value, locale));
    }
    case "SALES_VELOCITY": {
      const value = metric();
      return value === undefined ? t.unavailable : t.velocity(formatNumber(value, locale, 1));
    }
    case "DAYS_OF_COVER": {
      const value = metric();
      return value === undefined ? t.unavailable : formatDays(value, locale, messages.common.dayForms);
    }
    case "REPLENISHMENT":
      if (typeof c.known !== "boolean") return undefined;
      return c.known ? t.replenishmentKnown : t.replenishmentUnknown;
    case "AD_STATE": {
      const name = text("name") ?? text("externalId");
      const status = text("ownStatus");
      if (!name || !status) return undefined;
      return t.ad(name, label(t.adStatus, status));
    }
    case "AD_MAPPING": {
      const scope = text("scope");
      const verification = text("verification");
      if (!scope || !verification) return undefined;
      return t.mapping(label(t.mappingScope, scope), label(t.verification, verification));
    }
    default:
      return undefined;
  }
}
