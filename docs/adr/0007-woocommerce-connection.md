# ADR-0007: WooCommerce connection: secrets, authentication and SSRF protection

- Status: Accepted (owner acceptance, 2026-11-14)
- Date: 2026-11-14
- Related: document 04 §10 (Integrations), ADR-0004 (store scope), ADR-0005 (data access), backlog B-12

## Context

B-12 connects an existing WooCommerce store through a REST API key (consumer key and consumer secret). It is the first
customer secret in the system and the first network calls to an address supplied by the user. The decisions about
storing secrets, the authentication method and restricting addresses are hard to reverse, because they concern
data we will store in the database and the attack surface of the backend.

Constraints:

- Secrets never come back from the backend and never reach logs, the audit or the browser after they are saved (04 §10).
- The local test store runs over HTTP (`http://localhost:8081`). WooCommerce accepts HTTP Basic only over
  HTTPS. Over HTTP it requires an OAuth 1.0a signature.
- The user supplies the store address, so the backend must not become a proxy into the internal network (SSRF).
- KMS and production hosting are chosen in F4. The B-12 solution must be portable without a format migration.

## Decision

1. **Model.** The `integration_connection` table (one connection per provider per store: `UNIQUE (store_id,
   provider)`, only `WOOCOMMERCE` to start) stores the address, the status and the result of the last check. Secrets
   are in a separate `integration_credential` table (connection, name, ciphertext, nonce, key identifier). Lists and
   DTOs read only the first table, so a view query cannot fetch a secret by accident. Both tables have the
   composite FK to the store (ADR-0004). For display we store only a hint: the last 4 characters of the key.
2. **Encryption.** AES-256-GCM, a random 96-bit nonce per write, AAD = `connection_id|store_id|name`
   (a ciphertext cannot be moved to another connection or store). A 32-byte key in base64 from the
   `INTEGRATION_SECRETS_KEY` variable, with the identifier `INTEGRATION_SECRETS_KEY_ID`. Without the key the
   application does not start. The `local` profile has an explicit development key that must never reach another
   environment. The `key_id` column lets F4 move to envelope encryption with KMS without a schema change, by
   re-encrypting the records.
3. **Authentication.** Over HTTPS: HTTP Basic with the key and the secret (the WooCommerce recommendation). Over HTTP:
   one-legged OAuth 1.0a with HMAC-SHA256, allowed only with `operator.integration.woocommerce.allow-insecure-http=true`
   (`local` profile only). Otherwise an `http://` address is rejected when it is saved.
4. **Address protection (SSRF).** Store address: `https` only (or `http` with the flag above), without credentials,
   query or fragment, normalized to `scheme://host[:port][/path]`. Before every call the host is resolved and
   rejected if any of its addresses is loopback, private, link-local, CGNAT (100.64/10), IPv6 unique-local,
   multicast or unspecified. Exception: `allow-private-network=true` (`local` only). The client does not follow
   redirects and has a 5 s connect limit, a 10 s read limit and a response size limit.
   API paths are fixed (`/wp-json/wc/v3/...`); the user does not supply a resource path.
5. **Connection check.** Reads only: `products?per_page=1`, `orders?per_page=1` and the store currency from
   `settings/general/woocommerce_currency`. The result is a status (`CONNECTED` or `ERROR`) and an error code with a
   fix step: `AUTH_FAILED`, `PERMISSION_DENIED`, `UNREACHABLE`, `TLS_ERROR`, `NOT_WOOCOMMERCE`,
   `ADDRESS_NOT_ALLOWED`, `UNEXPECTED_RESPONSE`. A currency mismatch with the store setting is a warning.
   The check runs synchronously in the request (3 reads with time limits) and returns the result right away. This is
   a deliberate simplification compared with 04 §10 (202 + operation ID). We move to an asynchronous check when a
   provider with a longer process appears (Meta, mail).
6. **API** (per 04 §10, B = `/api/v1/stores/{storeId}`): `GET B/integrations`,
   `POST B/integration-connections` `{provider, storeUrl}`, `POST B/integration-connections/{id}/credentials`
   `{consumerKey, consumerSecret}` and `POST B/integrations/{id}/checks`. Reading requires `STORE_READ`, changes
   require `STORE_MANAGE`; saving secrets calls `StoreAccess.reauthorize` right before the write. Every change and
   check writes an audit event without secrets. New secrets or a new address set the status back to
   `CHECK_REQUIRED`. Synchronization (`sync-jobs`) comes in B-13, disconnecting (`disconnect`) later.
7. **Key permissions.** A `read` key is enough. A read-only check cannot safely detect that the key also has
   write access, so the UI asks for a read-only key, and the backend never writes to Woo in the Pilot.
8. **Frontend.** The form in Integrations sends the secrets through a Server Action to the backend in the POST body.
   `password` fields without autocomplete; the values are not stored in the session, the URL or the application
   state.

## Alternatives considered

- **Secrets in `integration_connection` columns.** Simpler, but every `SELECT *` or new list query could fetch
  them. Rejected.
- **pgcrypto in the database.** The key would have to reach SQL (parameters, query logs). Rejected.
- **KMS right away.** Requires the F4 hosting decision. The `key_id` column keeps that path open.
- **Basic over HTTP locally.** WooCommerce does not accept it, and it builds the habit of sending a secret in plain
  text. Rejected in favor of OAuth 1.0a behind a local flag.
- **The WooCommerce authorization flow (`/wc-auth/v1/authorize`).** More convenient for the user, but it needs a
  public callback and a choice of backend address. To be considered for onboarding (F6); the secret format stays.

## Consequences

- Losing `INTEGRATION_SECRETS_KEY` means store owners have to enter their keys again.
  Rotating the key requires re-encryption (the tool comes only in F4).
- Checking DNS before the call leaves a DNS rebinding window between the check and the connection. In F4,
  outbound traffic to stores goes through an egress proxy with the same address policy.
- The import's system actor (B-13) reads secrets with a system scope (`STORE_READ`). Decryption is available
  only inside the `integration` module, not in its public API.

## Verification

- Unit tests: encryption (the AAD binds the ciphertext to the connection and the store, a swapped nonce or
  ciphertext is detected), the OAuth 1.0a signature (test vector), address validation and the private network block.
- API integration tests: store isolation, `STORE_MANAGE` for changes (ANALYST gets 403), the secret never comes back
  in any response or in the audit, error mapping from a fake HTTP server.
- Manually and in a CI smoke test: the connection check against the local WooCommerce (HTTP + OAuth 1.0a) returns
  `CONNECTED`.
