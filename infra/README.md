# infra: local environment (B-05)

Every password and key in this directory is **local only**. Never use them in any other environment.

## Start

```bash
docker compose -f infra/docker-compose.yml up -d --wait
```

The first start takes about 1–2 minutes. The one-off `woo-seed` container installs WordPress and WooCommerce,
creates the test store and exits with code 0. Later starts skip the seed (`operator_seed_version`).
Full reset: `docker compose -f infra/docker-compose.yml down -v`.

## Services

| Service | Address | Access |
| --- | --- | --- |
| PostgreSQL 17 | `localhost:5432` | database `operator`, user/password `operator`; Keycloak has a separate database `keycloak` |
| Temporal (dev server) | `localhost:7233`, UI http://localhost:8233 | no auth |
| Keycloak 26 | http://localhost:8180 | admin: `admin`/`admin`; realm `operator` |
| Mailpit | SMTP `localhost:1025`, UI http://localhost:8025 | no auth |
| Jaeger 2 | OTLP HTTP `localhost:4318`, UI http://localhost:16686 | no auth; backend with `OTEL_EXPORT_ENABLED=true` |
| WooCommerce 11.2 (WordPress 7.1) | http://localhost:8081 | wp-admin: `admin`/`admin` |

The backend (`backend/`) connects to this database by default: `./gradlew bootRun --args='--spring.profiles.active=api'`.

## Keycloak: realm `operator`

Imported from [`keycloak/operator-realm.json`](keycloak/operator-realm.json) on the first start.

- Client `operator-web`: confidential (server-side session in Next.js, B-09), Authorization Code + PKCE, redirect
  only `http://localhost:3000/auth/callback`, secret `local-operator-web-secret`.
- Client `operator-dev-cli`: public, password grant only, **local only** — for API calls with curl.
- Both clients add the `operator-api` audience to the token; the backend rejects tokens without it.
- The access token is valid for 60 s, so the frontend's session refresh is exercised all the time (ADR-0006).
- Users `owner-a` and `owner-b` (password = login) have fixed IDs (`1111…`, `2222…`) that the backend's `local`
  profile refers to. Per-store roles are not in Keycloak but in our database (ADR-0004).

Changing the realm: edit the JSON and run `down -v` (the import only works for a new realm).

## WooCommerce: test data

| Product | SKU | Stock |
| --- | --- | --- |
| Koszulka Basic (variable, attribute Rozmiar/size) | `TSHIRT-BASIC-S` / `-M` / `-L` | 18 / 3 / 0 |
| Kubek Logo (simple) | `MUG-LOGO` | 49 |
| Serum nawilżające (variable, attribute Pojemność/volume) | `SERUM-30` | 60 |

`SERUM-30` is the Inventory Guardian scenario (document 03 §2, seed version 2): 49 completed one-unit orders, 7 a
day over the 7 full days before the seed ran (Europe/Warsaw, the demo stores' time zone). Lowering its stock to 18
gives 2.6 days of cover. The dates are relative to the seed run, so for the scenario after a few days reset the
store with `down -v`. A store seeded with version 1 gets the product on the next `up` (with orders from that day).
The ad fixture of store B maps the dedicated ad `ad-9001` (verified) and the catalog ad `ad-9002` to it.

Currency PLN, country PL, timezone UTC, stock management enabled. Product names are Polish on purpose: the test
store mirrors a Polish shop.

REST API key (read only): `ck_local_operator_0000000000000000000000` /
`cs_local_operator_0000000000000000000000`.

> The local store runs over HTTP. WooCommerce then accepts only an **OAuth 1.0a** signature (Basic Auth and keys in
> the query string work only over HTTPS). The WooCommerce adapter (B-12) has to handle this, or the local store
> gets TLS; decided in B-12.

### Webhooks locally (B-14, ADR-0009)

Woo in Docker delivers webhooks to the API on the host through `http://host.docker.internal:8080` (the
`host-gateway` entry in compose). The seed installs the mu-plugin `operator-local-webhooks.php`, which allows
private addresses in `wp_safe_remote_request`; this setting is for the local store only.

1. In Integrations (profile `api,local`) generate and save the webhook secret, then copy the delivery URL.
2. In wp-admin → WooCommerce → Settings → Advanced → Webhooks add 4 webhooks (Order created/updated,
   Product created/updated), status "Active", API version "WP REST API Integration v3", with that URL and secret.
3. Woo delivers asynchronously (Action Scheduler on WP-Cron), so after a change visit any store page or run
   `wp action-scheduler run`. A wp-cli container started separately needs
   `--add-host host.docker.internal:host-gateway`, because it is the one that sends the webhooks then.

Offline installation: set `WOOCOMMERCE_SOURCE` in the `woo-seed` service to the path of a mounted
`woocommerce.zip` (by default the GitHub release is downloaded).
