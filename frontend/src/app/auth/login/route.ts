import { NextResponse, type NextRequest } from "next/server";

import { startLogin } from "@/lib/oidc";
import { safeReturnTo } from "@/lib/return-to";
import { setLoginCookie } from "@/lib/session";

export const dynamic = "force-dynamic";

/** Starts Authorization Code + PKCE with Keycloak; state, nonce and verifier stay in an encrypted cookie. */
export async function GET(request: NextRequest) {
  const returnTo = safeReturnTo(request.nextUrl.searchParams.get("returnTo"));
  const { url, transaction } = await startLogin(returnTo);
  const response = NextResponse.redirect(url, 303);
  await setLoginCookie(response, transaction);
  response.headers.set("Cache-Control", "no-store");
  return response;
}
