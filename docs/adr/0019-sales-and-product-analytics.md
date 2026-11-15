# ADR-0019: Sales and product analytics v1: definitions, coverage and the MetricDTO

- Status: Proposed
- Date: 2026-11-15
- Revised: 2026-11-15 (review: comparisons guarded against incomplete or incomparable coverage)
- Related: document 04 §3 (MetricDTO, ComparisonDTO), §7 (Analytics), document 05 §C (Metrics & Evidence Engine),
  document 01 §6 (U-02), ADR-0011 (metric values), ADR-0015 (history import, coverage), backlog B-34

## Context

The pilot shows sales and product analytics next to the Inventory Guardian, so the owner can check what a proposal is
based on. The numbers must come from versioned definitions, never from the model; an unknown value is `null` with a
reason, never zero; and the orders we have imported may cover only part of a chosen period while the 12-month history
is still importing. WooCommerce orders carry totals, tax, discount and shipping, but refunds are not imported yet.

## Decision

1. **Definitions (module `metrics`, new versions of `MetricDefinition`).**
   - `net_sales@v1`: for qualifying orders (`QUALIFYING_ORDER_STATUSES_V1`) placed in the window,
     `total − tax − shipping`, in the store currency. WooCommerce totals are after discounts. Refunds are not imported,
     so the value is `ESTIMATED` with the limitation `REFUNDS_NOT_INCLUDED`.
   - `qualifying_orders@v1`: the number of those orders. `CONFIRMED` on a covered window.
   - `aov@v1`: `net_sales / qualifying_orders` on the same basis, scale 2, half-up; `null` with `NO_ORDERS_IN_WINDOW`
     when there are no orders.
   - `variant_net_sales@v1`: the sum of the variant's line totals (after discounts, without tax) in qualifying orders.
   - Orders in another currency than the store's are left out of every amount and reported as the limitation
     `OTHER_CURRENCY_EXCLUDED`; no conversion happens in v1.
2. **Periods and coverage.** A request names local dates `from..to` (inclusive) in the store timezone, at most 366
   days. Values are computed only over the part of the window inside the order coverage (ADR-0015 watermark); a
   partly covered window gives `ESTIMATED` with `SALES_HISTORY_PARTIAL` and the covered range in the meta; a window
   outside the coverage gives `UNAVAILABLE` with `SALES_HISTORY_INCOMPLETE`.
3. **Comparisons only between comparable periods.** The base is the previous period of the same length, computed the
   same way. A change (absolute and percentage) is returned only when every condition holds:
   - Both periods are fully inside the order coverage. A partly covered period on either side gives a `null` change
     with `COMPARISON_COVERAGE_MISMATCH`. The two covered ranges are in the meta, so the UI can say why.
   - Both periods are complete local days. A period that includes the current local day is still running, which
     gives `COMPARISON_PERIOD_INCOMPLETE`. The default periods of the UI end with yesterday.
   - Both values use the same definition version and the same basis. An excluded other-currency share above 5 % of
     the orders on either side gives `COMPARISON_BASIS_DIFFERS`. Below that, the change is returned and the
     limitation is listed.
   - The base is not zero and not unavailable (`COMPARISON_BASE_ZERO`, `COMPARISON_BASE_UNAVAILABLE`).

   The comparison's quality is the lower of the two values', and every limitation of either side is listed. The
   comparison is never computed over only the covered part of a period. That would compare different numbers of
   days, or a part of the week with a whole one, and invent a trend.
4. **MetricDTO.** The API returns every number as `{key, value, unit, currency, quality, period, definitionVersion,
   sourceRefs, observedAt, missingComponents, comparison}`; values are decimal strings. The daily series has a point
   per local day, with `null` for an uncovered day.
5. **Products.** `GET /analytics/products` lists variants with units, `variant_net_sales`, current stock and days of
   cover (the Inventory Guardian definition, ADR-0011), sorted and paged in the backend (at most 100 rows per page).
   The variant detail adds the daily units and the stock readings, the mapped ads and the variant's decisions.
6. **Placement.** The calculations live in `metrics`; the analytics read models that combine metrics, ads and
   decisions live in `reporting`, which may now read `identity` for the trusted scope. Nothing here runs a model or
   changes data.

## Alternatives considered

- **Net sales including shipping.** Document 04 asks for an explicit choice; we exclude shipping in v1 because
  shipping is a pass-through cost for most pilot stores. A store-specific choice belongs to the Store Business Profile
  later and would be `net_sales@v2`.
- **Treating an uncovered day as zero sales.** Rejected: it fabricates a trend (document 04 §7).
- **Comparing only the covered parts of both periods.** It would always give a number, but the parts can differ in
  length and in the days of the week they contain, and the result would look exact while it is not.
- **Currency conversion.** Needs a rate source and its version; out of scope until a pilot store sells in two
  currencies.

## Consequences

- Refunds (and their effect on net sales) need an import change and `net_sales@v2`.
- Profitability and Ads analytics wait for costs (F5) and Meta insights; they reuse the MetricDTO and the coverage
  rules.

## Implementation status

The analytics on the pilot branch compute the comparison whenever the base is known and non-zero, and they mark a
partly covered period only on its own value. After this revision is approved, a follow-up adds the comparison
guards, their reasons and the default periods ending yesterday.

## Verification

- Golden tests for every definition on a fixed order set: qualifying statuses, tax and shipping, other currencies,
  zero orders, partial and missing coverage, the previous-period comparison.
- Comparison tests for each guard:
  - partial coverage on either side;
  - a running current day;
  - an other-currency share above and below the threshold;
  - a zero base and an unavailable base;
  - a comparison whose quality is the lower of the two.
- API tests for store isolation, the date limits and paging.
