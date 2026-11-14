import "server-only";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import createClient from "openapi-fetch";

import type { components, paths } from "@/api/schema";

import { serverConfig } from "./config";
import { readSession, SESSION_COOKIE, type Session } from "./session";

export type StoreResponse = components["schemas"]["StoreResponse"];
export type MeResponse = components["schemas"]["MeResponse"];
export type ApiError = components["schemas"]["ApiError"];

export async function currentSession(): Promise<Session | null> {
  return readSession((await cookies()).get(SESSION_COOKIE)?.value);
}

export function loginPath(returnTo: string): string {
  return `/auth/login?returnTo=${encodeURIComponent(returnTo)}`;
}

/**
 * Typed backend client for Server Components and Route Handlers, generated from the OpenAPI contract.
 * The access token comes from the server-side session and never leaves the server. Responses are not
 * cached: every request is evaluated for the current user and store (04 §15, UX-13).
 */
export async function backend(returnTo: string) {
  const session = await currentSession();
  if (!session) {
    redirect(loginPath(returnTo));
  }
  return createClient<paths>({
    baseUrl: serverConfig().backendUrl.toString().replace(/\/$/, ""),
    headers: { Authorization: `Bearer ${session.accessToken}` },
    fetch: (request: Request) => fetch(request, { cache: "no-store" }),
  });
}

/** A 401 from the backend means the session is no longer accepted: sign in again, keep the place. */
export function redirectIfUnauthenticated(status: number, returnTo: string): void {
  if (status === 401) {
    redirect(loginPath(returnTo));
  }
}
