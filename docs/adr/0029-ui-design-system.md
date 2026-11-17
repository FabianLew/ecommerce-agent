# ADR-0029: UI design system on CSS tokens and cascade layers, without a component framework

- Status: Proposed
- Date: 2026-11-16
- Related: ADR-0006 (Next.js frontend), ADR-0013 (typed message catalogs), document 04 §2 and §14,
  `docs/ui/ui-ux-redesign-plan.md` (decisions D1–D6, iteration R1), the accepted concepts in `docs/ui/concepts/`

## Context

The pilot frontend had one `globals.css` with raw colour values, a 16 px base meant for reading rather than for a
dense operations tool, statuses shown as plain text, and no shared primitives. The UI/UX redesign needs a single,
consistent visual language before the screens are rebuilt (R2 onwards), without touching the backend, the API or
the business logic, and without hiding warnings or blocks. Owner decisions: 14 px interface text and 15 px reading
text (D1), lucide-react icons (D2), a light theme only for now (D5).

## Decision

1. **Plain CSS with design tokens.** All colours, the type scale, spacing, radii, control heights, the sidebar width,
   content containers and gutters are CSS custom properties in `frontend/src/styles/tokens.css`. Components and pages
   use tokens, never raw values.
2. **Cascade layers** in a fixed order, declared once: `tokens, base, layout, components, pages`. A page style can
   override a component without specificity tricks; there is no second design system and no utility framework.
3. **No UI component framework** (no MUI, Chakra, Tailwind, shadcn). The few primitives the screens need are small
   React components in `frontend/src/components/ui/` (`Icon`, `StatusBadge`, `Callout`) on top of the existing
   class names (`.button`, `.card`, `.badge`, `.tabs`, `.table`, `.dialog`), which are restyled onto the tokens.
4. **Status tones.** Every API status maps to one of five tones (success, warning, danger, info, neutral) in
   `tones.ts`, checked with `satisfies Record<…>` so a new API status fails the type check until it has a tone. A
   status is always icon + text + colour (WCAG 1.4.1). A problem state never maps to success; a unit test guards it.
5. **Fonts self-hosted** through Fontsource (Inter Variable, JetBrains Mono 400): no request to a third-party font
   CDN, so no visitor data leaves the application, and the build is reproducible.
6. **Icons:** lucide-react, stroke 1.75, decorative (`aria-hidden`), always next to text.
7. **Type base:** 14/20 px for the interface, 15/24 px for reading text. Containers of 760, 1280, 1440 and 1680 px
   keep line lengths readable on wide screens.
8. **Light theme only.** Tokens are named by role, so a dark theme can be added later as another token set if it is
   justified.
9. **Accessibility gate:** the opt-in screenshot matrix `frontend/e2e/screens.spec.ts` checks every main view at
   390–2560 px for sideways scrolling and fails on a serious or critical axe violation (WCAG 2.2 AA tags).

## Alternatives considered

- **Tailwind CSS.** Fast to write, but it spreads design decisions across markup, adds a build dependency and
  invites one-off values; the token set here is small enough for plain CSS.
- **A component library (MUI, Chakra, Mantine).** Brings its own visual language and theming runtime, which works
  against a distinct product look and adds weight to server components.
- **shadcn/ui with Radix.** Good accessibility primitives, but it pulls in Tailwind and copies many components we do
  not need yet. It can be reconsidered for a specific complex widget (e.g. a combobox) when a screen needs one.
- **Fonts from Google Fonts.** Simpler, but sends visitor IP addresses to a third party.

## Consequences

- One place to change the look; screens rebuilt in later iterations reuse the same tokens and primitives.
- Accessible widgets (menus, comboboxes, popovers) have to be built carefully by hand or adopted one by one.
- New status codes from the API need a tone before the frontend type-checks.
- Inter adds about 130 kB of woff2 when both the latin and latin-ext subsets (Polish text) load; the browser loads
  only the subsets a page uses.

## Verification

- `frontend/src/components/ui/tones.test.ts` (tone fallback, problem states never success).
- `E2E_SCREENS=1 pnpm test:e2e e2e/screens.spec.ts` against the local stack after the Inventory Guardian scenario:
  10 views × 5 widths, no sideways scrolling, 0 serious or critical axe violations (R1 report).
- Contrast ratios of the tokens in `frontend/README.md`.
