import "server-only";

import * as client from "openid-client";

import { serverConfig } from "./config";
import type { LoginTransaction, Session } from "./session";

let configuration: Promise<client.Configuration> | undefined;

function isLocalhost(url: URL): boolean {
  return url.hostname === "localhost" || url.hostname === "127.0.0.1";
}

/** OIDC discovery for the confidential client operator-web (Authorization Code + PKCE). */
function oidc(): Promise<client.Configuration> {
  if (!configuration) {
    const { issuer, clientId, clientSecret } = serverConfig();
    if (issuer.protocol !== "https:" && !isLocalhost(issuer)) {
      throw new Error("OIDC_ISSUER must use https outside localhost");
    }
    configuration = client
      .discovery(issuer, clientId, clientSecret, undefined, {
        // Plain HTTP is accepted only for the local Keycloak from infra/docker-compose.yml.
        execute: issuer.protocol === "https:" ? [] : [client.allowInsecureRequests],
      })
      .catch((error: unknown) => {
        configuration = undefined;
        throw error;
      });
  }
  return configuration;
}

function redirectUri(): string {
  return new URL("/auth/callback", serverConfig().appUrl).toString();
}

export async function startLogin(returnTo: string): Promise<{ url: URL; transaction: LoginTransaction }> {
  const config = await oidc();
  const transaction: LoginTransaction = {
    state: client.randomState(),
    nonce: client.randomNonce(),
    codeVerifier: client.randomPKCECodeVerifier(),
    returnTo,
  };
  const url = client.buildAuthorizationUrl(config, {
    redirect_uri: redirectUri(),
    scope: "openid",
    response_type: "code",
    code_challenge: await client.calculatePKCECodeChallenge(transaction.codeVerifier),
    code_challenge_method: "S256",
    state: transaction.state,
    nonce: transaction.nonce,
  });
  return { url, transaction };
}

/** Exchanges the code; openid-client checks state, PKCE, nonce and the ID token signature and claims. */
export async function finishLogin(callbackSearch: string, transaction: LoginTransaction): Promise<Session> {
  const config = await oidc();
  const callbackUrl = new URL(redirectUri());
  callbackUrl.search = callbackSearch;
  const tokens = await client.authorizationCodeGrant(config, callbackUrl, {
    pkceCodeVerifier: transaction.codeVerifier,
    expectedState: transaction.state,
    expectedNonce: transaction.nonce,
    idTokenExpected: true,
  });
  const claims = tokens.claims();
  if (!claims) {
    throw new Error("ID token missing");
  }
  return toSession(claims.sub, tokens);
}

export async function refresh(session: Session): Promise<Session> {
  const tokens = await client.refreshTokenGrant(await oidc(), session.refreshToken);
  return toSession(session.sub, tokens, session);
}

/** Ends the Keycloak session from the server (no browser redirect needed, no ID token stored). */
export async function endProviderSession(session: Session): Promise<void> {
  const config = await oidc();
  const endpoint = config.serverMetadata().end_session_endpoint;
  if (!endpoint) {
    return;
  }
  const { clientId, clientSecret } = serverConfig();
  await fetch(endpoint, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: clientId, client_secret: clientSecret, refresh_token: session.refreshToken }),
    cache: "no-store",
  });
}

function toSession(
  sub: string,
  tokens: client.TokenEndpointResponse & client.TokenEndpointResponseHelpers,
  previous?: Session,
): Session {
  const now = Math.floor(Date.now() / 1000);
  const refreshToken = tokens.refresh_token ?? previous?.refreshToken;
  if (!refreshToken) {
    throw new Error("Refresh token missing");
  }
  // Keycloak returns refresh_expires_in; without it the session lasts as long as the previous one.
  const refreshExpiresIn = Number(tokens.refresh_expires_in);
  return {
    sub,
    accessToken: tokens.access_token,
    accessTokenExpiresAt: now + (tokens.expiresIn() ?? 60),
    refreshToken,
    refreshTokenExpiresAt: Number.isFinite(refreshExpiresIn) && refreshExpiresIn > 0
      ? now + refreshExpiresIn
      : previous?.refreshTokenExpiresAt ?? now + 1800,
  };
}
