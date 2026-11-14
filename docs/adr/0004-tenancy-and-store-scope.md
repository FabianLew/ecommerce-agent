# ADR-0004: Tenancy model and trusted store scope

- Status: Accepted with corrections (owner review, 2026-11-14; corrections in items 2, 3, 5, 6, 8 and 9)
- Date: 2026-11-14
- Related: plan (decision I6, B2 "tenancy risks"), document 02 §3 (`RunContext`), document 04 §3, backlog B-06, B-08

## Context

One organization (tenant) has many stores, and a user may have access to many stores with different
roles (I6). The store scope must be the same in HTTP, in Temporal activities and in the Agent Runtime, and
neither the LLM nor a payload may set it. A data leak between stores is the most serious risk of the product.

## Decision

1. **Model:** `organization` → `store`; `app_user` identified by the (`issuer`, `subject`) pair from the OIDC
   token; `store_membership` (organization, store, user, role) — one row per role.
2. **Every store-dependent business table has `organization_id` and `store_id` and a composite foreign key
   (`store_id`, `organization_id`) → `store (id, organization_id)`.** This applies to all such tables,
   not only `store_membership`: the database will not accept a row that links a store to another organization. The
   rule is checked by a schema test (`IdentitySchemaTest`) that automatically covers every new table with a
   `store_id` column. The only documented exception is `audit_event` (events without an established scope, e.g.
   a denied access to a non-existent store).
3. **`TrustedStoreScope` (organization, store, actor, roles, permissions)** is the only scope type passed
   to modules. The actor is a sealed type:
   ```java
   sealed interface Actor {
       record UserActor(UUID userId) implements Actor {}
       record SystemActor(String service) implements Actor {}
   }
   ```
   `UserActor` is an operation on behalf of a signed-in user; `SystemActor` is an operation of a backend
   service without a user (e.g. an agent monitoring the store at night). **The type alone is not a safeguard.**
   The `TrustedStoreScope` constructor is not public; the scope is built only by the trusted `StoreAccess`
   factory, which checks the data in the database:
   - `scopeFor(userId, storeId)`: the user's current membership in the store;
   - `systemScope(service, organizationId, storeId)`: that the store exists in this organization, when a
     system process starts from a stored record (e.g. a store monitoring schedule);
   - HTTP: the user from the token and `{storeId}` from the path;
   - Temporal (B-08): `StoreContextPropagator` carries identifiers in the workflow headers
     (`organizationId`, `storeId`, the actor: `userId` **or** `service`) and the `correlationId`. The header is only
     a **declaration**: before every activity, `StoreScopeActivityInterceptor` rebuilds the scope in
     `StoreAccess` (`scopeFor` for a user, `systemScope` for a service) and only then exposes
     `ActivityStoreScope.current()`. A missing, ambiguous (both actors) or mismatched declaration ends the
     activity with a non-retryable `STORE_SCOPE_MISSING` / `STORE_SCOPE_REJECTED` error (with an audit entry).
     So a workflow of a user whose access was revoked will not execute the next activity;
   - Agent Runtime: the actor and the store come from the trusted run context, never from model arguments or a
     payload.
   Repository queries take `organizationId` and `storeId` from this object (ADR-0005).
4. **No access and no resource give the same `404 NOT_FOUND` response** (document 04 §3), so that the
   existence of other stores is not revealed. An attempt to access another or a non-existent store is audited
   (`STORE_ACCESS_DENIED`). A store member without the required permission gets `403 FORBIDDEN`
   (the existence of the store is not a secret to them) and a `STORE_PERMISSION_DENIED` event.
5. **Roles and minimal permissions from B-06.** Working roles from document 04 §11 [P]: `OWNER`,
   `DECISION_OPERATOR`, `SUPPORT_OPERATOR`, `ANALYST`. Code checks permissions, not role names.
   Two permissions are enforced right away:

   | Permission | Meaning | Roles |
   | --- | --- | --- |
   | `STORE_READ` | reading store data, decisions and reports | all roles |
   | `STORE_MANAGE` | store settings, integrations, memberships | `OWNER` |

   A store endpoint requires `STORE_READ` by default; an endpoint that changes the store declares `STORE_MANAGE`.
   The system scope has only `STORE_READ`: system actions go through the policy and executor path
   (`AuthorizedAction`), not through a broader scope. The full matrix (e.g. approving decisions, customer
   support) is settled in O-13, and this table is extended then. Roles live in our database, not in Keycloak
   (Keycloak only authenticates).
6. **A permission change takes effect immediately, including for earlier approvals.** A scope proves only that
   the check passed at the moment it was built. Right before a sensitive action (executing an
   `AuthorizedAction`, changing settings, sending), the executor calls `StoreAccess.reauthorize(scope, permission)`,
   which reads the membership and roles from the database again. An approval from a few hours ago does not let a
   user whose access or role was revoked act; the action ends with a denial and an audit entry. This applies
   independently of the 24-hour approval validity and the store state revalidation (I1).
7. **Audit:** the `audit_event` table is append-only (a trigger blocks `UPDATE` and `DELETE`), in the
   `identity` module, so that every module can use it in line with the dependency direction. The event actor is
   `actor_user_id` or `actor_service` (never both).
8. **Retention and minimization of personal data in the audit (requirement).**
   - The audit stores identifiers (user, store, resource), not personal data: no e-mails, no customer message
     content and no order data in `details`.
   - Before the pilot (F4) we introduce a retention policy with a retention period (a separate ADR) and its
     execution by a system job, which is the only thing allowed to delete expired entries.
   - The trigger protects against an application bug, **not against a privileged database administrator** (who
     can disable the trigger or change the data). The target is that the application connects with a role
     without `UPDATE`/`DELETE` rights on `audit_event`, and a separate migration role owns the table; any
     immutability against the administrator (export to external storage) is an F4 decision.
9. **A second line of defense (PostgreSQL RLS)** is enabled before the pilot (F4, E-45), as planned.

## Alternatives considered

- **Roles and stores in Keycloak token claims** — rejected: a permission change would need a new token,
  and the store data model is in our database anyway; Keycloak stays a replaceable identity provider.
- **`403` for another store** — rejected: it reveals that the resource exists (04 §3).
- **RLS right away** — postponed: it needs `SET LOCAL` in every transaction and complicates migrations and service
  queries; the plan is scope in repositories + isolation tests now, RLS in F4.

## Consequences

- Every new store endpoint takes a `TrustedStoreScope` as a parameter; no scope = no access.
  An endpoint that changes the store marks the parameter `@RequiresPermission(STORE_MANAGE)`.
- Every new store table gets the composite foreign key in the same migration; otherwise the schema test fails.
- Executors of sensitive actions (B-18+) must call `reauthorize` right before execution; executor tests
  check the denial after a membership is revoked.
- A user's first request registers them in `app_user` (`USER_REGISTERED` in the audit). Invitations and
  membership management are a separate task (Settings → Team).
- Isolation tests (`StoreIsolationTest`) are part of the safety tests and block the merge.

## Verification

`backend/`, `./gradlew check` (Testcontainers PostgreSQL), PR B-06:

- `StoreIsolationTest` (JWT tokens in MockMvc): store isolation, indistinguishable 404s, `403` for a member without
  `STORE_MANAGE`, audit of both denials, a revoked membership takes effect from the next request.
- `StoreAccessTest`: actor and permissions in the scope, a system scope only for a store in the given organization,
  `reauthorize` denies after a membership is revoked and after a role change.
- `IdentitySchemaTest`: append-only audit, the actor is either a user or a service, a guard for composite foreign
  keys (with a test that it detects a table without the key).
