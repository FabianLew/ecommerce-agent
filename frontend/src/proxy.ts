import { NextResponse, type NextRequest } from "next/server";

import { refresh } from "@/lib/oidc";
import { clearSessionCookie, needsRefresh, readSession, sealSession, SESSION_COOKIE, setSessionCookie }
  from "@/lib/session";

/**
 * Every page requires a session. Expiring access tokens are refreshed here, because only the proxy and
 * Route Handlers may set cookies; the refreshed session is also passed on to the page being rendered.
 */
export async function proxy(request: NextRequest) {
  const session = await readSession(request.cookies.get(SESSION_COOKIE)?.value);
  if (!session) {
    return toLogin(request);
  }
  if (!needsRefresh(session)) {
    return NextResponse.next();
  }
  try {
    const renewed = await refresh(session);
    request.cookies.set(SESSION_COOKIE, await sealSession(renewed));
    const response = NextResponse.next({ request: { headers: request.headers } });
    await setSessionCookie(response, renewed);
    return response;
  } catch {
    const response = toLogin(request);
    clearSessionCookie(response);
    return response;
  }
}

function toLogin(request: NextRequest) {
  const login = new URL("/auth/login", request.url);
  login.searchParams.set("returnTo", request.nextUrl.pathname + request.nextUrl.search);
  return NextResponse.redirect(login, 307);
}

export const config = {
  matcher: ["/((?!_next/|auth/|signed-out|healthz|favicon.ico).*)"],
};
