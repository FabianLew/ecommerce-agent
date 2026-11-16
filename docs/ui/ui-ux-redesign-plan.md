# UI/UX Redesign Plan: Demo-Ready Product Experience

- Status: Direction approved 2026-11-16 (decisions in section 9). Implementation is gated on the Visual Concept
  Gate (section 6.1): no R1 work before the owner approves the concepts.
- Date: 2026-11-16
- Branch: `pilot` (frontend at 3df0b2b)
- Inputs: Product Readiness Audit of 2026-11-15 (screenshots 01–37), `docs/architecture/04-UI-UX-SPECIFICATION.md`,
  ADR-0006 (frontend and session), ADR-0013 (UI languages), ADR-0020 (approval, AuthorizedAction, ledger),
  ADR-0025 (monitoring start), ADR-0026 (inventory protection levels), the landing page direction (palette and tone).

## 0. Goal and boundaries

Turn the current frontend from a technical prototype into a premium, global B2B SaaS that can be shown to store
owners without the caveat "the frontend is still a draft". References for the level of craft: Linear, Mercury, Ramp.
We do not copy them. We avoid the usual AI look: purple gradients, glow, heavy animation, decorative dashboards.

The product is a platform-independent Commerce Operator. WooCommerce and Meta Ads are the first integrations, not the
shape of the interface: navigation, page titles and empty states talk about "your store", "advertising", "data
sources"; provider names appear only where a provider is the subject (an integration card, a target ad).

**This is a UX redesign, not a logic change.** Out of scope and unchanged: the Policy Engine, approval and
`AuthorizedAction` semantics, tenancy boundaries (`TrustedStoreScope`, `StoreAccess`), AI governance and budgets,
Temporal workflows, external mutation mechanisms, the API contract. Every redesign PR touches `frontend/` and docs
only. If a screen needs data the API does not return, the plan says so and the screen works without it; a backend
change, if ever needed, is a separate, explicitly approved PR.

No warning, block or safety-relevant fact is removed to make a screen look cleaner. Section 7 lists them.

## 1. Current state (audit)

### 1.1 Stack and structure

| Area | Today | Assessment |
| --- | --- | --- |
| Framework | Next.js 16 App Router, React 19, server components, server actions; no client data fetching | Keep. Sound base: forms work without JS, tokens never reach the browser. |
| UI library | None. One global `app/globals.css` (799 lines), utility-ish class names added per backlog item (`/* B-12 */`, `/* B-32 */`) | Keep "no UI framework", but restructure the CSS: it grew by appending, with repeated badge variants and ad hoc rules. |
| Components | 7 shared components (`AppShell`, `Sidebar`, `StoreSwitcher`, `PageHeading`, `StatePanel`, `MetricCard`, `DailyBars`); the rest is markup inline in 12 page files (up to 283 lines each) | Too few primitives: every page re-implements cards, lists, badges and messages. |
| Design tokens | 13 CSS custom properties on `:root` (04 §2 palette, measured contrast in `frontend/README.md`) | Good seed, the contrast work stays valid. Missing: neutral scale, semantic status set, type scale, spacing, radius, elevation, z-index, widths. |
| Typography | `font-family: Inter, system-ui…` but Inter is never loaded, so the font depends on the machine. Body 16 px, page title 28 px, card title 18 px, metric 28 px bold | Inconsistent rendering between machines; hierarchy too flat and too large for a dense operational UI. |
| Spacing | Mixed `px` and `rem` (`gap: 1rem` next to `gap: 16px`); no scale variable | Normalise to one 4 px scale. |
| Icons | None. Status is text plus a pill | 04 §2 asks for text **and** icon on statuses. |
| Links | Browser default blue and underline in content (see screenshots 27, 33, 34) next to the green accent | Visibly unfinished. |
| Layout width | `.content { max-width: 1200px }`, left-aligned; on 1920 px and wider about 40% of the screen is empty on the right | Needs adaptive containers (section 2.3). |
| Responsiveness | One breakpoint (900 px: sidebar becomes a toggled block above content), one at 640 px (tables become blocks) | Works, but the mobile menu pushes content down instead of a drawer; tables lose their header semantics on phones. |
| Accessibility | Good foundations: skip link, focus on page heading after navigation, `:focus-visible`, `prefers-reduced-motion`, 44 px targets, native `dialog`, `role=status/alert`, native `select` store switcher | Keep all of it. Gaps: status by colour/pill only, some headings out of order (`h1` inside `StatePanel` under a page `h1`), no automated a11y check. |

### 1.2 Navigation, routing and store switching

- Routes: `/stores/:storeId/{dashboard|decisions|support|analytics|automations|activity|integrations|settings}`, plus
  `/onboarding`, `/notifications`, `/decisions/:proposalId`, `/activity/:activityId`,
  `/analytics/products/:variantId`, `/integrations/ads`. Locale in a cookie, not in the URL (ADR-0013).
- The sidebar shows all eight sections of 04 §2. **"Customer support" and "Automations" are placeholders** that open
  "This view is not available yet" (`[section]/page.tsx`). In a demo this is the first thing an owner clicks.
- Notifications sit at the bottom of the nav as a ninth item, without an unread counter. The decision counter from
  04 §2 ("counters of pending decisions") is missing, although the API returns `counts` and `unreadCount`.
- Onboarding is not reachable from the navigation at all, only from the Overview attention list.
- `StoreSwitcher` is a native `<select>` labelled "STORE"; the option text is "Sklep A · Demo". Switching keeps the
  section and drops resource IDs (`switchStorePath`, unit-tested). The logic is right; the presentation is a form
  control, not a product element.
- Top bar: store name, "Time zone: Europe/Warsaw", user name, a "Polski" button, a "Sign out" button. No
  breadcrumbs, no data freshness indicator (both in 04 §2).

### 1.3 States

| State | Today |
| --- | --- |
| Loading | No `loading.tsx` anywhere. Pages render only after every backend call returns; navigation feels frozen. |
| Error | One root `error.tsx` that replaces the whole shell (sidebar disappears). Inline errors use `form-message error`. |
| Empty | `StatePanel` (info/warning/error) with title + description. Copy is honest but generic ("Nothing needs attention right now"); empty metrics show a large "No data" headline. |
| Success | `form-message` green box; badges "Connected", "Done". |
| Running | `RefreshWhileRunning` polls the integrations page while a sync runs. |

### 1.4 Translations

- Two typed catalogs (`i18n/en.ts` 954 lines, `pl.ts` 912 lines); English defines the type; a missing Polish text
  fails `typecheck`. Keep this, it is a strength.
- Problems seen in the audit:
  - Raw numbers from the API: notifications print `daysOfCover` as sent, "covers about **2.4286** days" (screenshot
    33); the same field is formatted to 1 decimal elsewhere ("2.3 days").
  - No plural handling: "1 confirmed single-variant ads"; Polish needs one/few/many forms.
  - Technical vocabulary in the default view: "episode", "proposer", "Revision 1", "Definition: net_sales@v1",
    "opportunityId", raw codes in "Missing" and in Meta errors.
  - The proposer summary is backend-generated English and is shown in the Polish UI marked `lang="en"`.
  - Mixed tone between screens ("Get the store ready", "The approved action was checked again and authorized").

### 1.5 Use of space (1440 / 1920 / ultrawide)

Screenshots 31, 34 and 20 show the pattern: one column of full-width cards, each a label/value list on the left
half; KPI cards are tall because each carries a badge, two muted lines and a definition string; the 7-bar chart is
180 px tall with bars of equal height (no y-axis, no values); the Decision Detail is 2,500 px long with the approve
action at the top and the evidence at the bottom, nothing side by side. At 1920 px the content stops at 1,200 px.

### 1.6 Keep or rebuild

| Item | Decision | Notes |
| --- | --- | --- |
| Server components, server actions, `backend()` client, session, proxy | **Keep, untouched** | Not part of the redesign. |
| `i18n/*` catalogs, `getMessages`/`useMessages`, cookie locale | **Keep**, extend | Add a plural helper and a number/duration formatter; rewrite copy per screen. |
| `lib/store-path.ts` (`SECTIONS`, `switchStorePath`, `decisionPath`) | **Keep**, extend | Split `SECTIONS` into "routes" and "visible in navigation". |
| `PageHeading` (focus after navigation) | **Keep** behaviour, wrap in a new `PageHeader` | |
| `StatePanel` | **Refactor** into `EmptyState` + `Callout` | Fix heading level (no second `h1`). |
| `MetricCard` | **Rebuild** UI, keep `metricText` and the "never a fake zero" rule | Definition and quality move into a compact footer/tooltip. |
| `DailyBars` | **Rebuild** as `TrendChart`, keep the gap semantics (a day without data is not drawn as zero) | Adaptive width, y-axis gridlines, value labels, table toggle. |
| `DecisionCommands` | **Keep logic byte for byte** (idempotency keys, ETag, `useActionState`, dialog per command); restyle only | Highest regression risk; see section 7. |
| `ExecutionTimeline` | **Restyle** | Same steps and texts. |
| `Sidebar`, `AppShell`, `StoreSwitcher` | **Rebuild** UI | Keep the store switching semantics and the a11y features. |
| Integration forms (`WooCommerceForm`, `MetaForm`, `WebhookSetup`, `MappingForm`) | **Keep** forms and actions, move into a connection flow page | |
| `globals.css` | **Restructure** in place into layers (one design system, not a second one) | Section 2.9. |

## 2. Design direction

One direction: **calm, precise, operational**. White work surfaces on a faintly tinted canvas, hairline borders
instead of shadows, one green accent used sparingly for the primary action and the selected state, colour for
status only, tabular numbers, a compact type scale. It reuses the landing page's ink and green so marketing and
product feel like one brand, without the landing's dark forest hero, grid texture and lime highlights, which belong
to marketing.

### 2.1 Colour

Neutrals carry the interface; the accent is reserved. Values extend the 04 §2 palette, so the measured pairs in
`frontend/README.md` stay valid. Every new text/background pair is measured in R1 and must reach WCAG AA (4.5:1 for
text, 3:1 for control boundaries and icons).

| Token | Light | Use |
| --- | --- | --- |
| `--color-canvas` | `#F6F8F7` | Page background behind cards (04 §2) |
| `--color-surface` | `#FFFFFF` | Cards, tables, dialogs |
| `--color-surface-subtle` | `#FAFBFA` | Table headers, sidebar, inset areas |
| `--color-surface-hover` | `#F1F4F2` | Row and nav hover |
| `--color-border` | `#E3E9E5` | Decorative borders (1.2:1, never the only boundary of a control) |
| `--color-border-strong` | `#C9D3CD` | Dividers inside cards, table rules |
| `--color-control-border` | `#74867E` | Inputs, secondary buttons (3.85:1, 04 §14) |
| `--color-text` | `#172B25` | Primary text, values |
| `--color-text-secondary` | `#52665E` | Labels, descriptions (6.1:1) |
| `--color-text-tertiary` | `#6B7C75` | Timestamps, helper text (target ≥ 4.5:1, measured in R1) |
| `--color-accent` | `#16734B` | Primary button, selected nav item, links, focus ring |
| `--color-accent-hover` | `#115C3C` | Primary button hover |
| `--color-accent-subtle` | `#EAF5EE` | Selected nav background |

Semantic status colours. Each status is always **icon + text + colour**, never colour alone.

| Tone | Text/icon | Background | Border | Meaning |
| --- | --- | --- | --- | --- |
| `success` | `#16734B` | `#EAF5EE` | `#BFDCCB` | Confirmed, connected, passed, done |
| `warning` | `#8A5A00` | `#FFF6E0` | `#F0D9A8` | Needs attention, estimated, waiting for a person |
| `danger` | `#B42318` | `#FDECEB` | `#F4C7C3` | Error, failed, stopped changes, blocked |
| `info` | `#1D4F86` | `#EEF4FB` | `#C9DAEE` | Neutral information, in progress, shadow mode |
| `neutral` | `#52665E` | `#F1F4F2` | `#DCE3DF` | Not connected, not started, no data |

Rules: green means "primary action" or "confirmed", not "number went up"; numbers are ink. Red is used only for
real errors and the stop state. No gradients, no glow, no coloured shadows. Charts use the accent for the measured
series and `--color-border-strong` for gaps and axes.

### 2.2 Typography

- **Inter Variable**, self-hosted through `@fontsource-variable/inter` (works in offline Docker builds and the CI,
  unlike a build-time Google Fonts download). Features `tnum` for every number, `cv11` for a single-storey `a` in
  data. **JetBrains Mono** (also from Fontsource) only for technical identifiers: SKUs in technical sections, ad IDs,
  request IDs, hashes. Same pairing as the landing page, without its display face.
- Base UI size **14 px** (controls, tables, nav, labels) and **15 px** for reading text (decision explanations,
  onboarding descriptions). This deliberately changes 04 §2 ("body 16 px"); 16 px everywhere is what makes today's
  screens look oversized. Minimum 12 px for secondary labels stays. The 04 spec is updated in the same PR (R1).

| Role | Size / line height | Weight | Tracking |
| --- | --- | --- | --- |
| Display (Overview hero number only) | 32 / 40 | 600 | -0.02em |
| Page title (`h1`) | 24 / 32 | 600 | -0.015em |
| Section title (`h2`) | 16 / 24 | 600 | -0.005em |
| Subsection (`h3`) | 14 / 20 | 600 | 0 |
| KPI value | 28 / 36 | 600, tabular | -0.01em |
| Body (reading) | 15 / 24 | 400 | 0 |
| Body (UI), table cell | 14 / 20 | 400 | 0 |
| Label, column header | 12 / 16 | 500 | 0.01em, sentence case (no uppercase) |
| Mono | 13 / 20 | 400 | 0 |

### 2.3 Layout grid and adaptive width

Breakpoints: `sm` 640, `md` 900 (existing), `lg` 1280, `xl` 1600, `2xl` 2200.

| Container | Max width | Used by |
| --- | --- | --- |
| `readable` | 760 px | Forms, settings sections, onboarding step body, long text |
| `standard` | 1280 px | Lists (Decisions, Activity, Notifications) |
| `detail` | 1440 px | Detail pages with a side rail (Decision Detail, Integration detail) |
| `wide` | 1680 px | Overview, Analytics, Integrations catalog, wide tables |

- Content uses a 12-column grid with a 24 px gutter (16 px below `md`), page padding 32 px (`lg`+), 24 px (`md`),
  16 px (below).
- Containers are centred on the canvas; at ≥ 2200 px the extra space becomes even margins, we do not stretch text
  lines or tables beyond the cap.
- **Detail pages** (Decision Detail, Integration detail) at ≥ 1280 px: main column + a 360 px sticky side rail for
  status and actions. Below 1280 px the rail moves above the main column.
- **Overview** at ≥ 1600 px: 8/4 split (work on the left, sources and activity on the right); at 1280–1599 the KPI
  row stays 4-up and the panels stack; below 900 one column.

### 2.4 Spacing, radius, surfaces

- Spacing scale (4 px base): 2, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64 as `--space-*`. Card padding 20 px (24 at
  `xl`), gap between cards 16 px (24 at `xl`), stack inside a card 12 px.
- Radius: `--radius-sm` 6 px (controls, badges), `--radius-md` 8 px (cards, menus), `--radius-lg` 12 px (dialogs,
  drawers). Pills (999 px) only for counters.
- Elevation: flat by default (border only). `--shadow-overlay` for menus, popovers and dialogs only:
  `0 8px 24px rgb(23 43 37 / 10%), 0 1px 2px rgb(23 43 37 / 6%)`.
- Motion: 120 ms ease-out for hover/press colour, 160 ms for menus and drawers; nothing animates on page load; all
  disabled under `prefers-reduced-motion` (kept).

### 2.5 Component sizes

| Component | Spec |
| --- | --- |
| Button | Heights 32 (sm), 36 (md, default), 40 (lg, the one dominant action per page). Variants: `primary`, `secondary` (white + control border), `ghost`, `danger`. Icon + label; label states the scope ("Approve ad pause"). On coarse pointers (`@media (pointer: coarse)`) the minimum target is 44 px, as today. |
| Input, select, textarea | 36 px, 6 px radius, control border, 3 px accent focus ring; label above (14/600), hint below (13, secondary), error below with icon (danger). |
| Card | Surface, 1 px border, 8 px radius, header row (title + optional meta + actions), body, optional footer. No nested cards. |
| Table | Header 36 px on `surface-subtle`, rows 44 px (52 px with a secondary line), numbers right-aligned and tabular, row hover, whole-row link where the row opens a detail. Below `sm` a table becomes a card list that keeps every column as label/value (no hidden data, 04 §2). |
| Badge (status) | 22 px, 6 px radius, 12/500 text, 12 px icon, tone from 2.1. |
| Callout (alert) | Full-width inside its container, icon, title, text, optional action; tones from 2.1; `role="alert"` only for errors that appear after an action, otherwise `role="status"` or none. |
| Dialog | 480 px (confirm) / 640 px (forms), 12 px radius, overlay shadow, title, body, footer with actions right-aligned (primary last). |
| Side rail panel | 360 px, sticky at 24 px from the top, contains status, timing and actions. |

### 2.6 Navigation and sidebar

- Sidebar 248 px, `surface-subtle`, right hairline. Top: product mark + working name, then the **store switcher** as
  a button showing the store name and organisation, opening a menu of stores (links built with `switchStorePath`,
  keyboard and screen reader friendly disclosure pattern; no new dependency). Disabled look when there is one store.
- Navigation groups, only sections that exist:
  - (no label) **Overview**, **Decisions** with the "to approve" counter
  - **Insights**: Analytics, Activity
  - **Setup**: Integrations, Settings, and **Setup progress (n/4)** while monitoring has not been started
- **Customer support and Automations are removed from the navigation** until they exist. Their routes keep
  answering with an honest "not part of this version" state, so old links do not break. (`SECTIONS` stays the route
  list; a new `NAV_SECTIONS` drives the sidebar.)
- Bottom of the sidebar: the store's operating state, e.g. "Monitoring on · Proposals only" or "Pause after
  approval (L1)", and "Changes stopped" in danger tone (reads listed in 2.7 and section 8).
- Top bar (56 px): breadcrumbs on the left; on the right the data freshness indicator ("Data as of 22:10"), the
  notifications button with the unread count (opens `/notifications`), and a user menu (name, role in this store,
  time zone, language switch, sign out). The language switch stays a plain form (works without JS, ADR-0013).
- Below `md`: the sidebar becomes an off-canvas drawer with focus trap and Escape to close (replaces today's
  push-down block); the top bar shows the menu button and the store name.

### 2.7 Shared status, notification and alert components

- `StatusBadge` with a typed map from every API status to `{tone, icon, label}`; one map per domain (decision,
  execution, integration, health, sync, mapping, quality). Unknown codes render as neutral with the raw code, so
  nothing is hidden (same rule as `label()` today).
- `Callout` for blockers, warnings and confirmations inside a page.
- **Global safety banner** in the shell, under the top bar, when changes are stopped for the store or globally
  (`GET /mutation-stop`, already in the API): danger tone, reason, link to Settings. Today this state is visible only
  in Settings and on a blocked approve button. The store's action mode (proposals only vs. pause after approval,
  `GET /action-permissions/{actionType}`) is shown as the operating state at the bottom of the sidebar (2.6).
- Notifications page becomes a proper list (icon by severity, text, time, "Open decision" action, read/unread state)
  instead of a bulleted list; the top bar button shows the unread count.

### 2.8 Light and dark mode

Light is the primary and the only mode shipped in the main iterations. All tokens are semantic (`--color-surface`,
not `--white`), so a dark theme is a second token set plus a chart check, with no component changes. Estimated cost:
one small PR (R11), done only after the light redesign is approved. Theme choice would follow
`prefers-color-scheme` with an override in the user menu.

### 2.9 How the CSS is organised (one design system)

No Tailwind, no component framework, no CSS-in-JS: the app is server-rendered and small, and the current approach
works. `globals.css` is split into ordered cascade layers and files imported once from the root layout:

```
src/styles/
  tokens.css       @layer tokens     — all custom properties (2.1–2.5)
  base.css         @layer base       — reset, typography, links, focus, reduced motion
  components.css   @layer components — primitives in section 3.1 (one block per component)
  layout.css       @layer layout     — shell, containers, grid
```

Existing class names used by tests or many pages (`.button.primary`, `.card`, `.badge`, `.form-message`) keep
working during the migration and are removed when their last user is migrated. New primitives are React
components in `src/components/ui/` that emit these classes. There is no second design system: the old rules are
replaced in place, not duplicated.

Icons: **lucide-react** (ISC licence, tree-shaken, about 20 icons used) wrapped in one `Icon` component so the set
can be swapped. Alternative if a new dependency is not wanted: our own 20 inline SVGs; decision D-2.

## 3. Components to change

### 3.1 New primitives (`src/components/ui/`)

`Button`, `LinkButton`, `Icon`, `StatusBadge`, `Badge` (count), `Card` (+ `CardHeader`, `CardFooter`), `Callout`,
`EmptyState`, `DescriptionList`, `DataTable` (server-rendered, responsive card fallback), `SegmentedTabs`
(link-based, for groups and analytics tabs), `Field` (+ input, select, textarea, hint, error), `Dialog`
(wraps native `<dialog>`), `Disclosure` (for "Technical details"), `Stepper`, `Skeleton`, `PageHeader`
(breadcrumbs, title via `PageHeading`, description, actions), `Breadcrumbs`, `FreshnessIndicator`, `SideRail`.

Each is used by at least two screens in the plan; nothing is added "just in case".

### 3.2 Domain components

| Component | Change |
| --- | --- |
| `AppShell`, `Sidebar`, `StoreSwitcher`, `TopBar`, `UserMenu`, `NotificationsButton`, `SafetyBanner` | Rebuild (R2) |
| `MetricCard` → `KpiTile` | Rebuild (R6) |
| `DailyBars` → `TrendChart` | Rebuild (R6), keeps gap semantics and the table alternative |
| `DecisionRow` (inbox), `DecisionHeader`, `DecisionFacts`, `DecisionActionPanel`, `SafetyChecks`, `EvidenceList`, `TechnicalDetails` | New, extracted from the two decision pages (R4, R5) |
| `DecisionCommands`, `ExecutionTimeline` | Restyle only (R5) |
| `OnboardingStepper`, `OnboardingStepPanel`, `ReadinessSummary` | New (R7) |
| `IntegrationCatalog`, `IntegrationTile`, `ConnectionStatus`, `ConnectionGuide` | New (R8) |
| `ActivityList`, `NotificationList` | Restyle with primitives (R3, R9) |

## 4. Screen-by-screen plan

Screenshots referenced are in the audit folder (`product-readiness-audit/screenshots/`).

### A. Overview (`/dashboard`; screenshots 04, 09, 34)

The Overview is an **operations cockpit** (owner decision, 2026-11-16). It answers, in this order:

1. Is the operator monitoring my store? (operator status band: monitoring state, ad change mode, stop state, data
   source health)
2. Does anything need my attention? (decisions to approve, integration problems)
3. What was detected recently? (low stock warnings, proposals, confirmations, mapping changes)
4. Which actions are pending, executed or not executed?

Sales KPIs and the chart sit below as "Business pulse": important, but they do not define the product. The
concept in `docs/ui/concepts/overview.html` is the reference layout; the list below is the original outline and is
superseded where the concept differs.

1. **Header**: "Overview", period ("Last 7 full days · 8–14 Nov") and the freshness indicator.
2. **Setup banner** (only while onboarding is incomplete): "Finish setup to start monitoring · 3 of 4 steps done"
   with one action. Replaces the attention-list item.
3. **Needs attention** first and full width: decisions waiting for approval as compact rows (problem in plain words,
   recommendation, time left to decide, "Review" button), then integration problems and other attention items with
   their fix link. When empty: a quiet success line "Nothing needs your attention" and when the last check ran, not
   a big empty card.
4. **KPI row**: one tile per metric the API returns (net sales, orders, ad spend, contribution margin), 4-up at
   ≥ 1280 px. Value, comparison when the API has one, a small quality marker ("Estimated: refunds not included
   yet"). Metric definitions (`net_sales@v1`) move into an info tooltip/disclosure. A metric without data shows
   "Not available" plus the reason and the step that unlocks it (for example "Ad spend is imported after you connect
   an advertising account" → Integrations). Never a zero, never a placeholder number, no savings or margin the
   backend did not compute.
5. **Sales trend**: full-width chart with y-axis gridlines, value on hover/focus, the dotted gap for days without
   data, and a "Show as table" toggle. Link to Analytics.
6. **Side column** (≥ 1600 px; below it stacks): **Data sources** (each connected source with a status badge and
   "data until"), **Recent activity** (five items, business phrasing, link to all).
7. The **Store summary** card (roles, permissions, currency) leaves the Overview and moves to Settings → Store and
   the user menu (role).

### B. Decisions inbox (`/decisions`; screenshots 14, 27)

- Segmented tabs "To approve · Monitoring · Completed" with counts (unchanged groups and API).
- A list (table on desktop, cards on phones) with columns: **Problem** ("SERUM-30 runs out in about 2 days"),
  **Recommendation** ("Pause the ad 'Serum 30 ml – dedicated ad'"), **Status** badge, **Decide by** (relative +
  absolute on hover), whole row clickable. Policy outcome shows as the status badge's secondary line.
- Empty states per group explain what the group holds and why it is empty, e.g. To approve: "No decisions are
  waiting for you. The operator checks stock and ads every few minutes and will list proposals here."
- Pagination ("Show more") kept; a stale cursor keeps the current recovery path.

### C. Decision Detail (`/decisions/:proposalId`; screenshots 15, 28–31)

Reference: `docs/ui/concepts/decision-detail.html`, which shows the waiting, changes-stopped, check-failed and
executed states. The page separates five parts, each in its own titled section: **Problem**, **Business impact**,
**Recommended action** (with what changes, what does not change, and what is deliberately left running),
**Evidence** (with **Safety checks**), and **Approval and execution** (side rail). Technical data is in a
disclosure; failed safety checks, the action scope and blocking reasons are always visible. On phones the full
approval panel follows the evidence ("content first, then the decision"), and a compact sticky action bar keeps the
main command one tap away: the action, the deadline and "Approve ad pause", or, when approval is blocked, the
short reason and "See why" instead of the button. The bar opens the same confirmation dialog as the panel. On
tablets the panel sits above the content.

**Data rule (owner decision, 2026-11-16):** the implementation shows only data the API returns today. Counters such
as "products watched", "active ads" or "last check N minutes ago" are not shown; existing statuses replace them
(monitoring start time, orders complete until, ads last read, integration health, decision group counts). Any
counter that needs a new API field is proposed separately and is not part of the redesign.

The core of the product. Two layers: what an owner needs to decide, then advanced detail. Layout: main column +
sticky side rail at ≥ 1280 px.

**Header**: breadcrumbs "Decisions / SERUM-30", title as the problem in plain words ("Serum nawilżające – 30 ml is
about to sell out"), a status line with the lifecycle badge (Waiting for your approval / Approved / Paused and
confirmed / Not executed…), the autonomy level ("Approval required · L1") and "Decide by 16 Nov, 22:13 (in 23 h)".

**Main column**, in this order:

1. **What is happening** — four fact tiles from `facts`: stock available to sell (16), sales per day (≈ 7), cover
   ("about 2.3 days", threshold 3 days), restock date (unknown). Stock confirmed time under the tiles.
2. **Why it matters** — one or two sentences from the reason codes, in business language: "A dedicated ad keeps
   sending buyers to a product that will run out before a restock date is known." Facts only: no estimated lost
   revenue or savings (the backend does not compute them).
3. **Recommendation** — the proposed action as a sentence, its exact scope ("Only this ad is paused. Other ads,
   budgets and campaigns are not changed."), what happens after approval (re-check, then execution, then reading
   the ad back) and that it can be undone in the ad account. Excluded ads ("not touched because…") listed here.
   When there is no action: the disposition explained (Monitor / Insufficient data / Escalate) and what would change
   it, missing requirements in plain words.
4. **What happened after the decision** — `ExecutionTimeline` restyled, shown right after the recommendation once
   the decision is approved or rejected (today it sits between summary and reasons).
5. **Evidence** — the cited data points as readable rows: "Stock reading · 16 units · observed 22:13", "Ad state ·
   Active · observed 22:12". The values come from the evidence `content` for known evidence types (a typed
   formatter per type, unit-tested); unknown types fall back to "Technical details". Raw JSON and hashes stay
   available per row in a disclosure.
6. **Safety checks** — policy outcome as a summary line ("All 7 safety checks passed · Requires approval"), with the
   check list visible. If any check did not pass, the list is expanded and the failing checks come first with a
   danger/warning callout. Never collapsed when something failed.
7. **Technical details** (disclosure, collapsed): proposer and version, policy key/version/mode, proposal and
   opportunity IDs, snapshot ID, request ID, revisions history, the proposer's original summary (labelled with its
   language when it differs from the UI language).

**Side rail** (the decision panel), always in view on desktop:

- Status badge and time left to decide.
- The primary action with its scope ("Approve ad pause") and "Reject" as secondary. Same `DecisionCommands`
  component: same dialogs, idempotency keys, ETag, pending state and result messages.
- **Every blocking reason stays visible here, above the buttons**: shadow mode, changes stopped, expired, missing
  permission, policy block. A disabled button always has its reason next to it (`aria-describedby`, as today).
- After approval: the execution state in one line ("Pause confirmed by reading the ad · 22:14") linking to the
  timeline.
- Read-only users see the panel without buttons and a line saying who can decide.

### D. Onboarding (`/onboarding`; screenshots 03, 18, 24, 25)

Today: four equal cards with "Open" buttons and a readiness table; the purpose of each step is not explained, and
"Start monitoring" is a grey button below a yellow note.

Target: a focused setup flow inside the shell.

- **Left**: vertical `Stepper` with the four backend steps (`CONNECT_STORE`, `CONNECT_TOOLS`, `BUSINESS_DATA`,
  `START_MONITORING`), each with its backend state (done / current / to do / blocked) and the overall progress
  ("2 of 4 done"). The current step is the backend's `nextStep`; the user can open any step.
- **Right**: the selected step panel: title in platform-neutral words ("Connect your store", "Connect advertising",
  "Add business details", "Start monitoring"), **why this step matters** (one sentence), **what we check** (the
  backend condition, e.g. "a successful read test and a first import"), blockers from the backend as callouts with
  their fix link, and **one dominant action** ("Connect WooCommerce", "Open business details", "Start monitoring").
  Secondary choices (Skip advertising for now, Map ads later) are ghost buttons with their consequence stated
  ("Ad protection stays off until an ad account is connected").
- **Start monitoring** step: a scope summary before the button: what is monitored, what the operator may propose,
  "Nothing is changed in your ad account; pausing ads after approval is switched on separately in Settings". The
  button is enabled only when the backend says so; otherwise the reason is shown.
- **Readiness** per domain (store data, ads, ad mappings, business profile) as a compact checklist under the
  stepper, from the API.
- **Done state**: "Monitoring is on since 22:20" with a link to the Overview.
- No step is shown as done unless the backend says `complete: true`; the UI never infers success from a
  submitted form. Read-only users see the same flow without actions and a line explaining why.

### E. Integrations (`/integrations`; screenshots 05–07, 11, 20–23)

Today: one long page with the WooCommerce card (details, health, sync, webhook setup, key form) and the Meta card,
about 2,000 px of forms.

Target: a **catalog** and a **detail flow per connection**.

- **Catalog** (`/integrations`): sections in a fixed order: **Commerce, Advertising, Analytics, Marketing,
  Support**. A section lists the integrations the product supports today (from a static frontend registry
  `integration-catalog.ts`: provider → category, name, one-line description, detail route). Today: WooCommerce in
  Commerce, Meta Ads in Advertising. **Categories with no supported integration are hidden entirely** (decision
  D-4): today the catalog shows only Commerce and Advertising, and Analytics, Marketing and Support appear when their
  first real integration ships. No placeholders, logos or "coming soon" cards.
- **Tile**: neutral monogram (no third-party logo files), name, description, one of three statuses: **Connected**
  (success), **Not connected** (neutral), **Attention required** (warning/danger) plus "Data until …" or the short
  reason. The mapping from the backend statuses (`status`, `health.status`, sync state, Meta status) to these three
  is one typed, unit-tested function; nothing reaches "Connected" unless the backend reports a successful
  connection and the health is not stale/error.
- **Detail** (`/integrations/woocommerce`, `/integrations/meta-ads`; the existing `/integrations/ads` stays as the
  ad mapping sub-page): header with status and the main action; sections **Status** (health, last check, data
  until, sync/import with history progress), **Connection** (masked key hint, change key), **Webhooks**
  (WooCommerce) or **Ad mappings** (Meta), and **Technical details** (delivery URL, IDs).
- **Connect flow**: "Connect" opens a guided page with numbered instructions next to the form (where to create the
  key in the platform, which permission, what we test), the existing form and server action, then the real check
  result. The webhook setup becomes a short checklist (topics, URL with copy button, secret generate/save) with
  the honest note that sync works without webhooks.
- Existing manual configurations (Woo key, webhook secret, Meta system-user token) stay; only their explanation
  and layout change.

### F. Other screens (consistency pass, R9)

Analytics (tabs as `SegmentedTabs`, KPI tiles, tables with `DataTable`), Activity (timeline list with icons and
business phrasing, filter chips kept), Activity detail, Notifications (2.7), Settings (sub-navigation: Store,
Autonomy & safety, Alerts, Business profile; the stop-changes control stays prominent and is never hidden behind a
tab without the global banner), Ad mappings, sign-in/sign-out pages, not-found and error pages, the "no stores"
state.

### G. States everywhere

- `loading.tsx` per store section with skeletons in the shape of the page (no spinners, no fake percentages);
  `RefreshWhileRunning` stays for running imports.
- `error.tsx` inside the store layout so errors keep the shell and navigation, with the request ID and "Try again".
- Empty states: what is missing, why, and the one action that fixes it; written per screen in 4.A–F.
- Success after an action: an inline `Callout` (`role=status`) near the action, not a toast that disappears.

## 5. UX and content rules

1. **Business language by default, technical detail on demand.** Codes, versions and IDs live in "Technical
   details" disclosures. Examples: "Revision 1" → shown only when there is more than one; "Cover 2.3 days" →
   "Stock lasts about 2.3 days at current sales"; "The episode status changed: closed" → "Case closed";
   "Definition: net_sales@v1" → tooltip.
2. **Numbers**: one formatter for each kind: days of cover 1 decimal ("about 2.4 days"), velocity 1 decimal, money
   via `formatMoney`, counts as integers, percentages 1 decimal. Raw API decimals are never printed (fixes "2.4286").
3. **Plurals**: a small `plural(locale, n, forms)` helper on `Intl.PluralRules`, used by both catalogs (Polish
   one/few/many). No i18n library (ADR-0013 holds).
4. **Times**: absolute in the store's time zone (as today) plus relative where urgency matters ("in 23 h").
5. **Polish catalog review**: one pass per screen PR, so EN and PL change together; terminology list (decision,
   approval, pause, monitoring, data source) agreed in R3 and kept in `frontend/README.md`.
6. **No dead ends**: no visible "view not ready" in the demo navigation; every empty state has a next step or says
   that nothing is needed.
7. **Provider-neutral wording**: "store", "advertising account", "data source"; provider names only on the
   provider's own tile, form and target names.
8. **Names and branding**: "E-commerce Operator" is a temporary working name (04 §2, O5). The UI keeps it in one
   constant and one wordmark slot; the mark is a neutral geometric glyph and no colour, shape or copy depends on
   the name. The target brand is global, English-language and premium, so the interface avoids local idioms and
   puns on the current name.

## 6. Implementation plan: small PRs

### 6.1 Visual Concept Gate (before R1, owner decision D-6)

Two static HTML/CSS concepts with realistic demo data, not connected to the backend and not touching production
components: `docs/ui/concepts/overview.html` and `docs/ui/concepts/decision-detail.html` (sources in
`docs/ui/concepts/src/`, assembled by `build.py`, no dependencies). They apply the tokens of section 2 and are
reviewed at 1440, 1920 and 390 px. The Decision Detail concept has a review-only switch for its four states. R1
starts only after the owner approves the concepts; agreed corrections are made in the concepts first, and the
implementation PRs then match them.

### 6.2 Iterations

Every PR targets `pilot`, is frontend + docs only, and ends with: before/after screenshots from the real app (local
stack with the demo data used in the audit) at 1440, 1920 and 2560 px plus 390 px (phone) and 1024 px (tablet);
`pnpm typecheck`, `pnpm test`, `pnpm build`, e2e green; green CI; a report (scope, tests, decisions, next PR) and a
stop for approval.

| PR | Scope | Notes / size |
| --- | --- | --- |
| **R0** | This plan (docs only) | Current PR |
| **R1** | Foundations: ADR for the UI design system (tokens, fonts, icons, CSS layers, base size change; updates 04 §2), `styles/` split, tokens, fonts, links, focus, base primitives (`Button`, `StatusBadge`, `Callout`, `Card`, `Field`, `Icon`), existing class names mapped to tokens so every screen improves without markup changes; screenshot capture spec (Playwright) and `@axe-core/playwright` check on the main pages | Medium. Visible change across the app, no layout change yet. |
| **R2** | App shell: sidebar with groups and counters, store switcher menu, hide Support/Automations from navigation, top bar (breadcrumbs, freshness, notifications button, user menu), safety banner (mutation stop / shadow mode), mobile drawer, adaptive containers, `loading.tsx` skeletons, store-level `error.tsx` | Medium. |
| **R3** | Content foundations: number/duration formatters, plural helper, terminology list, Notifications list redesign (fixes "2.4286"), Activity phrasing | Small. |
| **R4** | Decisions inbox | Small. |
| **R5** | Decision Detail: header, facts, why, recommendation, side rail with `DecisionCommands` (restyle only), timeline placement | Medium. Highest safety attention. |
| **R6** | Decision Detail: evidence formatters per evidence type, safety checks, technical details | Small/medium. Split from R5 to keep the review focused. |
| **R7** | Overview: attention first, KPI tiles, `TrendChart`, sources and activity column, setup banner | Medium. |
| **R8** | Onboarding flow | Medium. |
| **R9** | Integrations catalog + status mapping + detail routes | Medium. |
| **R10** | Integration connect flows (Woo key, webhooks, Meta token) and ad mappings page in the new layout | Medium. |
| **R11** | Consistency pass: Analytics, Activity detail, Settings sub-navigation, auth/error/not-found pages | Medium. |
| **R12** | Optional: dark theme (token set + chart check) | Small. Only if approved. |
| **R13** | Demo readiness check: full screenshot matrix EN + PL, a11y report, copy review, list of remaining gaps | Report, small fixes only. |
| **R14** | Usability test script (section 10) for 2–3 store owners or people outside IT, run after R4–R8 | Docs only. |

Implementation notes:

- **R1 (done):** ADR-0029; tokens, layers, fonts, icons, `StatusBadge`, `Callout`, tone maps; screenshot + axe spec.
- **R2 (done):** grouped sidebar (`NAV_GROUPS`) with the "to approve" counter and "Store setup n/4" while
  monitoring has not been started; store switcher and account menu as `details` disclosures (work without
  JavaScript); breadcrumbs without resource IDs; notifications button with the unread count; operating state
  (changes stopped, monitoring, effective ad pause level from `onboarding.monitoring.ads`); safety banner from
  `mutation-stop`; drawer below 900 px with focus trap and Escape; per-page containers; store `loading.tsx` and
  `error.tsx`. Four extra reads in parallel (`decisions?limit=1`, `notifications?limit=1`, `mutation-stop`,
  `onboarding`), measured page TTFB 55–76 ms median locally. **Not done:** the data freshness indicator in the top
  bar, because no store-level "data as of" value exists in the API (data rule); it stays on the pages that return
  it (e.g. integration health). A store-level freshness read would be a separate API proposal.
- **R3 (done):** plural-aware formatters (`plural`, `formatDays`, `formatCount`, `formatRelative`); notifications as
  rows; activity wording in terms of a "case"; product name in one constant (`lib/brand.ts`).
- **R4 (done):** decision inbox as a scannable list (problem, recommendation, status, deadline; whole row opens).
- **R5–R6 (done):** Decision Detail around the decision: fact tiles, why, recommendation with scope, execution,
  typed evidence values, checks with failures first, a sticky decision panel and a mobile action bar.
- **R7 (done):** Overview as an operations cockpit: setup callout, status band, needs-attention rows (to-approve
  decisions from the decisions API), business pulse (KPI tiles, chart with a table view), sources and recent
  activity. The store summary (roles, permissions) moved to Settings.
- **R8 (done):** Store setup as a stepper and one step panel (why it matters, what we check, blockers, one primary
  action, secondary choices with their consequence); start monitoring disabled with its reason until allowed.
- **R9–R10 (done):** integrations catalog from `integration-catalog.ts` with the tested tile status mapping;
  `/integrations/woocommerce` and `/integrations/meta-ads` detail pages with a guided connect flow; ad mappings
  show the mapping first and the form on demand. Server actions revalidate the whole integrations subtree.
- **R11 (done):** analytics toolbar, unit-aware metric values, activity feed grouped by day, activity and product
  detail headers, settings section navigation, state panels with icons and standalone pages with the product name.
- **R12:** not done; dark mode needs an owner decision (D5).
- **R13 (done):** demo readiness check, see `docs/ui/demo-readiness.md`.
- **R14 (done):** usability test script, see `docs/ui/usability-test-script.md`.

Order rationale: foundations and shell first so later PRs only compose primitives; Decisions before Overview
because it is the core and Overview reuses its rows; onboarding and integrations last among the big four because
they share the new integration status mapping.

## 7. Acceptance criteria

### 7.1 Global (every PR)

1. No change outside `frontend/` and `docs/`; no API contract change (`pnpm check:api` green); no change to server
   actions' request semantics (idempotency keys, ETag/If-Match, payloads).
2. `pnpm typecheck`, `pnpm test`, `pnpm build`, Playwright e2e and the full CI are green.
3. Both languages complete (typecheck enforces) and reviewed for the screens touched.
4. Before/after screenshots at 390, 1024, 1440, 1920, 2560 px; no horizontal page scroll at any width; at 1920 and
   2560 px the content uses its container (no single 1,200 px column on a wide screen).
5. Automated axe check: no serious/critical violations on the touched pages; keyboard path through every action;
   visible focus; statuses have text and icon; contrast AA measured for new pairs.
6. All existing `data-testid` hooks used by e2e keep working (or the tests are updated in the same PR with the
   reason).

### 7.2 Safety invariants (must hold after every PR; reviewed explicitly in R2, R5, R6, R8–R10)

1. Approve/Reject keep their confirmation dialogs, the approval scope text and the checks listed in the dialog.
2. A disabled approve button always shows its reason (shadow mode, mutations stopped, expired, permission, policy).
3. The mutation-stop state is visible on every store page while active (new banner) and in Settings.
4. An execution is shown as done only when the backend reports it verified; "unknown", "failed", "conflict" and
   "no-op" keep distinct texts and are never shown as success.
5. Failing policy checks, missing requirements and excluded ads are always visible without expanding anything.
6. Onboarding and integrations show "done/connected" only from backend state.
7. Read-only users never see enabled mutating actions; the UI keeps relying on `allowedActions` and permissions
   from the API (the backend remains the authority).
8. No invented numbers: no savings, margin, spend or "value protected" unless the API returns it.

### 7.3 Per area

- **Overview**: decisions waiting for approval appear above KPIs; each "Not available" metric names its reason and
  next step; at 1920 px KPIs, chart and side column fill the wide container.
- **Decisions**: problem, impact, recommendation, evidence and possible actions are each identifiable on the detail
  page without opening a disclosure; the action panel is visible without scrolling at 1440×900; all technical IDs
  are inside "Technical details".
- **Onboarding**: each step shows purpose, what is checked, blockers and exactly one primary action; refreshing the
  page keeps the state (backend-driven); "Start monitoring" is disabled with a reason until the backend allows it.
- **Integrations**: categories in the fixed order, only those with a supported integration; only real integrations listed; every tile has one of three
  statuses derived by the tested mapping; no button without a working action; connection forms are on the detail
  page.
- **Content**: no raw decimals (e.g. "2.4286") anywhere; plurals correct in EN and PL; no "view not ready" reachable
  from the navigation.

## 8. Regression risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Approve/reject flow broken by the restyle (dialog, idempotency key, ETag, pending state) | `DecisionCommands` logic untouched; R5 diff limited to markup/classes; the B-25 e2e scenario (`E2E_SCENARIO=1`) run locally for R5/R6 and reported. |
| Global CSS change breaks screens not yet redesigned | Cascade layers; legacy class names mapped onto tokens in R1; screenshot matrix of every page in every PR, not only the touched ones. |
| e2e selectors (`data-testid`, roles, texts) | Keep test IDs; copy changes update e2e in the same PR. |
| Store switching leaks a resource ID across stores | Menu links built only with `switchStorePath` (unit-tested); add a test for the menu. |
| Safety state hidden by a cleaner layout | Invariants 7.2 as a checklist in every PR description; an e2e assertion for the mutation-stop banner and blocked-approve reason. |
| Server-only/no-JS behaviour lost (forms, language switch) | New interactive pieces (menus, drawer, disclosure) progressive: `<details>`/native dialog where possible; forms remain plain forms. |
| Extra backend reads in the shell (counters, stop state, action mode) slow every page | At most four light reads (`decisions` counts, notifications `unreadCount`, `mutation-stop`, `action-permissions`) in parallel with the existing three; measured in R2; if slow, the counters move to the pages and only the stop state stays in the shell. |
| Fonts in standalone Docker build | Fontsource packages are bundled at build; verified with `docker build` in CI (already a job). |
| Polish strings overflow the new compact layout | Screenshots in both languages for each PR. |
| Scope creep into backend or new features | Section 0 boundaries; any needed API change is proposed separately. |

## 9. Decisions requested from the owner

| # | Question | Recommendation |
| --- | --- | --- |
| D-1 | Base UI text 14 px (reading text 15 px), changing 04 §2's 16 px | **Approved.** |
| D-2 | Icons from `lucide-react` (new dependency) | **Approved**, wrapped in one `Icon` component. |
| D-3 | Remove Customer support and Automations from the navigation until built | **Approved**; routes stay with an honest state. |
| D-4 | Integration categories with no integration | **Changed by the owner: hide such categories entirely.** |
| D-5 | Dark mode | **Approved as later and optional**, only if it proves justified. Tokens stay semantic. |
| D-6 | Visual concepts before code | **Approved and mandatory**: the Visual Concept Gate (6.1) precedes R1. |

Decided 2026-11-16 by the owner. Additional requirements from the same review: the Overview is an operations
cockpit (4.A), Decision Detail separates problem, impact, action, evidence and approval/execution (4.C), branding
stays name-independent (5.8), and a usability test follows the core screens (section 10).

## 10. Usability test (after the core screens)

After R4–R8, a short moderated test with 2–3 store owners or people outside IT, on the demo stack in English or
Polish, 30 minutes each, think-aloud, no help with the UI. The script is written as R14 and covers at least:

1. "Is the operator watching your store right now? How do you know?" (Overview status band)
2. "Is there anything you need to do today?" (needs attention)
3. Open the waiting decision: "What is the problem, why does it matter, and what exactly will happen if you
   approve?" Success: names the product, the sell-out timing, the single ad and what is not changed.
4. "Would you approve it? What would stop you?" Then switch to the changes-stopped state: "Why can't you approve?"
5. "Where would you check that the pause really happened?"
6. Onboarding: "What is the next step to finish setup, and why is it needed?"
7. Integrations: "Which data sources are connected and which need attention?"

Measures: task success, time to first correct answer, wrong turns, words people use for things (fed back into the
terminology list), and a 1–5 confidence rating for the approval decision. Findings become a short report and
small follow-up PRs; nothing in the safety invariants (7.2) is changed because of a usability finding without an
explicit owner decision.
