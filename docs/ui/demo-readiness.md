# UI redesign: demo readiness check (R13)

Status of the redesigned pilot frontend after R1–R11 (R12, dark mode, is not done: it needs an owner decision).
Checked on 2026-11-16 against a fresh local stack (`docker compose down -v`, then the Inventory Guardian scenario).

## 1. What was checked

| Check | Result |
| --- | --- |
| Inventory Guardian scenario on a fresh stack (connect WooCommerce, webhooks, import, stock drop, proposal, Decision Detail, activity) | Passes |
| Other e2e tests (sign-in, store switch, shell navigation, stop banner, language switch, read-only views, WooCommerce key errors) | 11 passed, scenario run separately |
| Unit tests (`pnpm test`) | 56 passed |
| Screenshot matrix: 10 views × 5 widths (390, 1024, 1440, 1920, 2560) × 2 languages (EN, PL) | 100 screenshots, no horizontal page scroll |
| Automated accessibility (axe, WCAG 2.2 A/AA tags), every view, both languages | 0 serious or critical violations |
| Backend, API contract, server action payloads | Unchanged (frontend and docs only) |

The matrix runs with `E2E_SCREENS=1 SCREENS_DIR=<folder> [SCREENS_LOCALE=pl] pnpm test:e2e e2e/screens.spec.ts`.

## 2. Safety invariants (plan section 7.2)

| # | Invariant | How it holds |
| --- | --- | --- |
| 1 | Approve/Reject keep their dialogs, scope text and checks | `DecisionCommands` dialogs unchanged; the mobile action bar opens the same dialog |
| 2 | A disabled approve button shows its reason | Blocked callout in the decision panel, linked with `aria-describedby`; "See why" in the action bar |
| 3 | Mutation stop visible on every store page and in Settings | Safety banner in the shell; Overview status band; Settings card |
| 4 | Execution shown as done only when verified | `ExecutionTimeline` texts unchanged |
| 5 | Failing checks, missing requirements and excluded ads visible without expanding | Failed checks are listed first with a danger callout; excluded ads in the recommendation section |
| 6 | Setup and integrations show "done/connected" only from backend state | Stepper uses `steps[].complete`; tiles use the tested `wooTileStatus` / `metaTileStatus` |
| 7 | Read-only users never see enabled mutating actions | Pages still rely on `allowedActions`, `canEdit`, `canCreate` and permissions |
| 8 | No invented numbers | Every number comes from the API; unavailable values say "No data" with the reason |

## 3. Copy review

- Terminology follows the table in the frontend README ("case", "decision", "Store setup", "Monitoring").
- Polish: "monitoring" is used consistently as the noun; the setup area is "Przygotowanie sklepu" in the
  navigation, the breadcrumb and the page title; references to "onboarding, step 4" now name "Store setup".
- Numbers: counts as whole numbers, days with one decimal and the right plural form (EN and PL), money with the
  store currency, dates in the store time zone.

## 4. Known gaps (proposed follow-ups)

| # | Gap | Why it is open | Proposal |
| --- | --- | --- | --- |
| G1 | No store-level "data as of" in the top bar | The API has no such value (data rule) | Separate API proposal |
| G2 | Raw platform codes still shown in places: ad status (`ACTIVE`), Meta sync error codes | No label catalog for these codes yet | Add label maps in a small PR |
| G3 | Product and variant names repeat ("Serum nawilżające – Serum nawilżające – 30 ml") | The UI joins product and variant names as the API returns them | Display rule: drop the product prefix when the variant name already contains it |
| G4 | Chart tick labels scale with the SVG and are small at 390 px | Single responsive SVG | Render ticks in HTML or reduce the number of ticks on narrow screens |
| G5 | Segmented tabs wrap to two lines at 390 px in Polish | Long labels | Horizontally scrollable tab bar on narrow screens |
| G6 | WooCommerce "Change the API key" form is always open | e2e drives it by its visible labels | Put it behind a disclosure and update the tests |
| G7 | Ad mapping uses a native multi-select | Kept the existing form and its action | Checkbox list with search |
| G8 | Business profile form is long | Kept the existing form | Group fields or split into steps |
| G9 | Dark mode (R12) | Needs an owner decision (D5) | Only if justified |
| G10 | Product name "E-commerce Operator" is temporary | Branding decision pending | One constant (`lib/brand.ts`) to change |
| G11 | Usability test not yet run | Script ready (R14) | Run with 2–3 people, then a findings report |
