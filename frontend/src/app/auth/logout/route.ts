import { NextResponse, type NextRequest } from "next/server";

import { serverConfig } from "@/lib/config";
import { endProviderSession } from "@/lib/oidc";
import { clearSessionCookie, readSession, SESSION_COOKIE } from "@/lib/session";

export const dynamic = "force-dynamic";

/**
 * Signs out: ends the Keycloak session from the server and clears the cookie. POST only, from our own
 * origin, so another site cannot sign the user out (CSRF).
 */
export async function POST(request: NextRequest) {
  const appUrl = serverConfig().appUrl;
  if (request.headers.get("origin") !== appUrl.origin) {
    return NextResponse.json({ code: "FORBIDDEN", message: "Cross-origin sign-out rejected.", retryable: false },
      { status: 403 });
  }
  const session = await readSession(request.cookies.get(SESSION_COOKIE)?.value);
  if (session) {
    try {
      await endProviderSession(session);
    } catch (error) {
      // The local session is cleared anyway; the provider session expires on its own.
      console.warn("Provider sign-out failed", error instanceof Error ? error.name : "unknown");
    }
  }
  const response = NextResponse.redirect(new URL("/signed-out", appUrl), 303);
  clearSessionCookie(response);
  return response;
}
