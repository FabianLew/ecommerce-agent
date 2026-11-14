# ADR-0006: Next.js frontend with a server-side session (BFF) and a client from the OpenAPI contract

- Status: Accepted (owner acceptance, 2026-11-14)
- Date: 2026-11-14
- Related: plan (decision I5: Spring Security + OIDC, Keycloak), document 04 §2–3, §14–15, backlog B-09,
  B-10, ADR-0001 (version rule), ADR-0004 (store scope)

## Context

B-09 requires a Next.js frontend with TypeScript, OIDC sign-in with a session in a cookie, `AppShell`, `StoreSwitcher`
and a client generated from OpenAPI. This is the first layer that holds user tokens, so the decisions about where
tokens live and how the frontend talks to the API are hard to reverse. Document 04 requires the backend to check
store access on every request, and the cache and the store context must not leak after an account or role switch
(UX-13).

Constraints: only GA releases older than about 2 weeks (ADR-0001), no extra infrastructure in the MVP
(e.g. Redis), small dependencies.

## Decision

1. **Stack:** Next.js 16.4.3 (App Router), React 19.3.1, TypeScript 5.9.3 in `strict` mode, Node 22 LTS,
   pnpm 10 (version in `packageManager`, installed by corepack). TypeScript 6/7 postponed: `openapi-typescript`
   still requires TS 5.
2. **BFF pattern: tokens never reach the browser.** The Next.js server is the confidential OIDC client
   `operator-web` (Authorization Code + PKCE + `state` + `nonce`, library `openid-client` 6.8.8). The browser
   gets only an encrypted session cookie. API calls go from Server Components and Route Handlers with the
   `Authorization: Bearer` header, without caching (`no-store`).
3. **The session is in one encrypted cookie, `op_session`:** JWE `dir` + `A256GCM` (`jose` 6.2.14), with keys
   derived from `SESSION_SECRET` through HKDF separately for the session and for the sign-in transaction. Attributes:
   `HttpOnly`, `SameSite=Lax`, `Path=/`, `Secure` on `https`, expires together with the refresh token. Content: `sub`,
   the access token, the refresh token and their expiry times. The ID token is checked at sign-in and is not stored.
   Measured cookie size: about 2.7–3.3 KB against a 4 KB limit; an e2e test guards a 3.5 KB threshold.
4. **Refresh:** `proxy.ts` (formerly middleware) refreshes the access token when less than 30 s remain and
   passes the new session to the rendered page. A failed refresh ends the session and leads to sign-in while
   keeping a safe `returnTo` (same-origin paths only).
5. **Sign-out:** `POST /auth/logout`, only from our origin (CSRF protection). The server ends the Keycloak session
   through the back channel (the `end_session` endpoint with the refresh token and the client secret) and clears
   the cookie.
6. **API contract:** springdoc-openapi 3.1.1 generates the `/api/v1` description in the backend tests. The file
   `contracts/openapi/operator-api.json` is in the repository, and `OpenApiContractTest` checks that it matches the
   running API. The frontend generates types from it (`openapi-typescript` 7.13.0) and uses `openapi-fetch` 0.17.0.
   CI checks that the generated file is up to date. The `/v3/api-docs` endpoint is disabled by default.
7. **Routing and the store:** every view has the `/stores/{storeId}` prefix (04 §2). The backend always verifies
   the store; for another or a non-existent store the UI shows a safe "unavailable" state and does not switch the
   store on its own. Switching the store keeps the section but drops the resource identifiers and parameters of
   the previous store.
8. **Tests:** Vitest 4.1.12 for logic (session, `returnTo`, store paths), Playwright 1.64.1 for e2e with
   real Keycloak, Postgres and the backend. Both in CI.

## Alternatives considered

- **Auth.js (next-auth)** — rejected: line 5 is still in beta (GA rule), and 4.x is an older line
  designed for the Pages Router. Our own sign-in code is about 200 lines on top of `openid-client`, which does
  the protocol verification (state, PKCE, nonce, ID token signature and claims).
- **Tokens in the browser (SPA + PKCE, public client)** — rejected: an access token reachable from JavaScript
  increases the impact of XSS; document 04 and the plan assume a session in a cookie.
- **Server-side session in Redis/Postgres** — postponed: it needs extra storage or session endpoints in the
  backend. We will come back to it when tokens no longer fit in a cookie or remote session revocation from the
  application is needed.
- **A hand-written API client** — rejected: type drift from the backend shows up only at runtime.

## Consequences

- Client components do not call the backend directly. When UI commands appear (B-24), we add a BFF proxy route
  in Next.js with CSRF protection (Origin + `SameSite`), with `Idempotency-Key` and `If-Match` per 04 §3.
- An API change requires: `./gradlew test --tests '*OpenApiContractTest' -PupdateOpenApi`, then `pnpm generate:api`.
- The local realm issues access tokens valid for 60 s, so that refresh is exercised all the time (also in e2e).
- The redirect URI of the `operator-web` client is only `/auth/callback` (no wildcards).
- The palette from 04 §2 was measured (frontend README): text and accent meet 4.5:1; the `#DFE7E2` border (1.26:1)
  is only decorative, form controls have a `#74867E` border (3.85:1).
- Risk: Keycloak may issue larger tokens (more claims), and the cookie would exceed the limit. Mitigation: the size
  test in e2e; then split the cookie or move to a server-side session.
- Risk: the refresh token in the cookie is encrypted, but a leak of `SESSION_SECRET` exposes sessions. The secret
  comes from the environment's secret store; rotating it invalidates all sessions.

## Verification

`frontend/`: `pnpm typecheck`, `pnpm test` (17 tests), `pnpm build`, `pnpm test:e2e` (6 scenarios against the
local stack): sign-in and store switching that keeps the section, a safe state for another store,
`HttpOnly` and no JWT in the page, sign-out ending the Keycloak session, rejecting a sign-out from a foreign origin,
token refresh. By mutation: disabling refresh makes the refresh test fail. Backend: `OpenApiContractTest`.
