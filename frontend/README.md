# Frontend (Next.js)

The operator's web application: sign-in through Keycloak, store selection, an `AppShell` with the eight sections
from document 04 §2. Decisions: [ADR-0006](../docs/adr/0006-frontend-and-session.md).

## Running locally

Needs Node 22 (`corepack enable` installs the pnpm version from `package.json`), the environment from
`infra/docker-compose.yml` and the backend with profiles `api,local`.

```bash
docker compose -f ../infra/docker-compose.yml up -d --wait postgres keycloak temporal
(cd ../backend && ./gradlew bootRun --args='--spring.profiles.active=api,local')   # separate terminal
cp .env.example .env.local
pnpm install
pnpm dev                         # http://localhost:3000, users owner-a / owner-b (password = login)
```

`owner-a` owns "Sklep A" and is an analyst in "Sklep C" (store switching, different permissions). `owner-b` sees
only "Sklep B".

## How the session works

- The browser gets only the `op_session` cookie (encrypted JWE, `HttpOnly`, `SameSite=Lax`). Tokens stay on the
  Next.js server; client components never call the backend directly.
- `src/proxy.ts` requires a session on every page and refreshes the access token before it expires.
- `/auth/login` → Keycloak (PKCE, state, nonce) → `/auth/callback`. `POST /auth/logout` also ends the Keycloak
  session.
- Server Components fetch API data through `backend()` from `src/lib/api.ts` (no cache).

## Languages

The interface is in English by default and in Polish as the second language (ADR-0013). Texts live in
`src/i18n/en.ts` and `src/i18n/pl.ts`; the English catalog defines the type, so a text missing in Polish fails
`pnpm typecheck`. Server components call `getMessages()`, client components `useMessages()`. The choice is kept in
the `op_locale` cookie and changed with the language button in the header or on the sign-out page. Store data
(names, products) is shown as entered and is not translated.

## API client

The types in `src/api/schema.d.ts` are generated from `../contracts/openapi/operator-api.json`. Do not edit them by
hand.

```bash
(cd ../backend && ./gradlew test --tests '*OpenApiContractTest' -PupdateOpenApi)   # after an API change
pnpm generate:api
```

CI (`pnpm check:api`) fails when the generated file does not match the contract.

## Tests

```bash
pnpm typecheck && pnpm test      # session logic, returnTo, store paths
pnpm build && pnpm test:e2e      # Playwright against the local stack (backend and Keycloak must be running)
```

Where Chromium is already installed, set `PW_CHROMIUM_PATH` instead of running `pnpm exec playwright install chromium`.

The Inventory Guardian scenario (`e2e/inventory-guardian-scenario.spec.ts`, B-25) runs only with `E2E_SCENARIO=1`.
It needs a fresh full stack (`docker compose -f infra/docker-compose.yml down -v && ... up -d --wait`), and the API
and the worker started with the e2e settings, which only shorten waits:

```bash
cd backend && ./gradlew bootJar
E2E=--spring.config.additional-location=file:../infra/e2e/application-e2e.yml
java -jar build/libs/ecommerce-agent-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=api,local $E2E &
java -jar build/libs/ecommerce-agent-backend-0.1.0-SNAPSHOT.jar --spring.profiles.active=worker,local $E2E &
cd ../frontend && pnpm build && E2E_SCENARIO=1 pnpm test:e2e
```

The scenario connects store B, so it passes once per fresh stack.

## Palette contrast (04 §2, §14)

Measured contrast ratios (WCAG 2.x) of the tokens in `src/styles/tokens.css`:

| Pair | Contrast | Use |
| --- | --- | --- |
| text `#172B25` on `#FFFFFF` / `#F6F8F7` | 14.91 / 13.98 | content |
| secondary `#52665E` on `#FFFFFF` / `#F6F8F7` / `#F1F4F2` | 6.14 / 5.76 / 5.54 | labels, descriptions |
| tertiary `#617269` on `#FFFFFF` / `#F6F8F7` / `#F1F4F2` | 5.10 / 4.78 / 4.60 | meta text, small captions |
| accent `#16734B` on `#FFFFFF`, white on accent | 5.85 | links, primary button |
| accent `#16734B` on `#EAF5EE` | 5.24 | active menu item, success badge |
| warning `#8A5A00` on `#FFF6E0` | 5.51 | warning badge and callout |
| danger `#B42318` on `#FDECEB` | 5.75 | error badge and callout |
| info `#1D4F86` on `#EEF4FB` | 7.54 | info badge and callout |
| neutral `#52665E` on `#F1F4F2` | 5.54 | neutral badge |
| border `#E3E9E5` / `#C9D3CD` on `#FFFFFF` | 1.23 / 1.53 | decorative only (cards, dividers) |
| control border `#74867E` on `#FFFFFF` | 3.85 | inputs, secondary buttons (3:1 requirement) |

## Styles (ADR-0029)

`src/styles/` holds the whole design system as plain CSS in cascade layers, imported once in `app/layout.tsx`:
`tokens.css` (colours, type scale, spacing, radii, control heights, containers; declares the layer order),
`base.css` (reset, typography, links, focus), `layout.css` (app shell), `components.css` (buttons, cards, badges,
callouts, forms, tables, tabs, dialog) and `pages.css` (styles of single views). Use tokens, never raw values. A
status is always icon + text + colour: `StatusBadge` and `Callout` in `src/components/ui/`, tones in `tones.ts`.

The screenshot matrix and the accessibility check of the redesign run against the local stack after the scenario:
`E2E_SCREENS=1 SCREENS_DIR=<folder> pnpm test:e2e e2e/screens.spec.ts` (widths in `SCREENS_WIDTHS`, default
390, 1024, 1440, 1920, 2560). It fails on a serious or critical axe violation and reports sideways scrolling.

## Content rules and terminology (plan section 5)

Numbers go through `src/i18n/format.ts`: days with `formatDays` (one decimal, correct word form), counts with
`formatCount`, money with `formatMoney`, other decimals with `formatNumber` (one decimal by default). Raw decimals
from the API are never printed. Plurals use `plural()` on `Intl.PluralRules` with per-language word forms (`*Forms`
keys in the catalogs; Polish has one/few/many and `other` for fractions).

| Concept | English | Polish | Avoid |
| --- | --- | --- | --- |
| A detected problem the operator tracks (opportunity) | case | sprawa | episode, opportunity |
| What the operator suggests (proposal) | recommendation | rekomendacja | proposal (except in technical details) |
| A person's yes to a recommendation | approval / approve | zatwierdzenie / zatwierdź | accept, confirm |
| Stopping an ad | pause | wstrzymanie / wstrzymaj | disable, turn off |
| Watching the store's data | monitoring | monitoring | tracking |
| Policy evaluation shown to people | safety checks | kontrole bezpieczeństwa | policy, guardrails |
| WooCommerce, Meta and later providers in general | data source, store, advertising account | źródło danych, sklep, konto reklamowe | provider names outside their own tile |
| Stopping all changes | changes stopped | zmiany wstrzymane | kill switch, mutations |

Provider names appear only on the provider's own tile, form and target names. Technical codes, versions and IDs live
in "Technical details" disclosures.
