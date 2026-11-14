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

## Palette contrast (04 §2, §14)

Measured contrast ratios (WCAG 2.x):

| Pair | Contrast | Use |
| --- | --- | --- |
| text `#172B25` on `#FFFFFF` / `#F6F8F7` | 14.91 / 13.98 | content |
| secondary `#52665E` on `#FFFFFF` / `#F6F8F7` | 6.14 / 5.76 | labels, descriptions |
| accent `#16734B` on `#FFFFFF`, white on accent | 5.85 | links, primary button |
| accent `#16734B` on `#EAF5EE` | 5.24 | active menu item |
| border `#DFE7E2` on `#FFFFFF` | 1.26 | decorative only (cards) |
| control border `#74867E` on `#FFFFFF` | 3.85 | inputs, secondary buttons (3:1 requirement) |
