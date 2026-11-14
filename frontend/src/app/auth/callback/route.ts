import { NextResponse, type NextRequest } from "next/server";

import { serverConfig } from "@/lib/config";
import { finishLogin } from "@/lib/oidc";
import { clearLoginCookie, LOGIN_COOKIE, readLoginTransaction, setSessionCookie } from "@/lib/session";

export const dynamic = "force-dynamic";

/** Completes sign-in. Any failure ends on the sign-in error page without details from the provider. */
export async function GET(request: NextRequest) {
  const appUrl = serverConfig().appUrl;
  const transaction = await readLoginTransaction(request.cookies.get(LOGIN_COOKIE)?.value);
  if (!transaction) {
    return failed(appUrl, "expired");
  }
  try {
    const session = await finishLogin(request.nextUrl.search, transaction);
    const response = NextResponse.redirect(new URL(transaction.returnTo, appUrl), 303);
    clearLoginCookie(response);
    await setSessionCookie(response, session);
    response.headers.set("Cache-Control", "no-store");
    return response;
  } catch (error) {
    console.warn("Sign-in callback rejected", error instanceof Error ? error.name : "unknown");
    return failed(appUrl, "rejected");
  }
}

function failed(appUrl: URL, reason: string) {
  const response = NextResponse.redirect(new URL(`/signed-out?error=${reason}`, appUrl), 303);
  clearLoginCookie(response);
  return response;
}
