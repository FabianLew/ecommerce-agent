import "server-only";

import type { NextResponse } from "next/server";

import { serverConfig } from "./config";
import { seal, unseal } from "./session-crypto";

export const SESSION_COOKIE = "op_session";
export const LOGIN_COOKIE = "op_login";

/**
 * Server-side session kept in an encrypted, HttpOnly cookie (ADR-0006). Tokens never reach browser
 * JavaScript. The ID token is validated at sign-in and not stored.
 */
export interface Session {
  sub: string;
  accessToken: string;
  /** Epoch seconds. */
  accessTokenExpiresAt: number;
  refreshToken: string;
  /** Epoch seconds; the session ends with the refresh token. */
  refreshTokenExpiresAt: number;
}

/** State of one sign-in attempt, kept between the redirect to Keycloak and the callback. */
export interface LoginTransaction {
  state: string;
  nonce: string;
  codeVerifier: string;
  returnTo: string;
}

const LOGIN_TTL_SECONDS = 600;

function secure(): boolean {
  return serverConfig().appUrl.protocol === "https:";
}

export async function sealSession(session: Session): Promise<string> {
  return seal({ ...session }, serverConfig().sessionSecret, "session",
    new Date(session.refreshTokenExpiresAt * 1000));
}

export async function readSession(value: string | undefined): Promise<Session | null> {
  const payload = await unseal<Session & Record<string, unknown>>(value, serverConfig().sessionSecret, "session");
  if (!payload || typeof payload.accessToken !== "string" || typeof payload.refreshToken !== "string") {
    return null;
  }
  return {
    sub: payload.sub,
    accessToken: payload.accessToken,
    accessTokenExpiresAt: payload.accessTokenExpiresAt,
    refreshToken: payload.refreshToken,
    refreshTokenExpiresAt: payload.refreshTokenExpiresAt,
  };
}

export async function setSessionCookie(response: NextResponse, session: Session): Promise<void> {
  response.cookies.set(SESSION_COOKIE, await sealSession(session), {
    httpOnly: true,
    secure: secure(),
    sameSite: "lax",
    path: "/",
    expires: new Date(session.refreshTokenExpiresAt * 1000),
  });
}

export function clearSessionCookie(response: NextResponse): void {
  response.cookies.set(SESSION_COOKIE, "", { httpOnly: true, secure: secure(), sameSite: "lax", path: "/", maxAge: 0 });
}

export async function setLoginCookie(response: NextResponse, transaction: LoginTransaction): Promise<void> {
  const expires = new Date(Date.now() + LOGIN_TTL_SECONDS * 1000);
  response.cookies.set(LOGIN_COOKIE, await seal({ ...transaction }, serverConfig().sessionSecret,
    "login-transaction", expires), {
    httpOnly: true,
    secure: secure(),
    // Lax: the cookie must come back on the top-level redirect from Keycloak to the callback.
    sameSite: "lax",
    path: "/auth/callback",
    expires,
  });
}

export async function readLoginTransaction(value: string | undefined): Promise<LoginTransaction | null> {
  const payload = await unseal<LoginTransaction & Record<string, unknown>>(value, serverConfig().sessionSecret,
    "login-transaction");
  if (!payload || typeof payload.state !== "string" || typeof payload.codeVerifier !== "string") {
    return null;
  }
  return {
    state: payload.state,
    nonce: payload.nonce,
    codeVerifier: payload.codeVerifier,
    returnTo: payload.returnTo,
  };
}

export function clearLoginCookie(response: NextResponse): void {
  response.cookies.set(LOGIN_COOKIE, "", { httpOnly: true, secure: secure(), sameSite: "lax", path: "/auth/callback",
    maxAge: 0 });
}

/** Refresh slightly before expiry so a request never reaches the backend with a token about to expire. */
export function needsRefresh(session: Session, nowSeconds = Math.floor(Date.now() / 1000)): boolean {
  return session.accessTokenExpiresAt - nowSeconds < 30;
}
