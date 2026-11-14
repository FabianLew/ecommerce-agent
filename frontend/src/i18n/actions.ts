"use server";

import { revalidatePath } from "next/cache";
import { cookies } from "next/headers";

import { serverConfig } from "@/lib/config";

import { isLocale, LOCALE_COOKIE } from "./locale";

const ONE_YEAR_SECONDS = 365 * 24 * 60 * 60;

/** Stores the interface language. Unknown values are ignored, so the cookie only ever holds a known locale. */
export async function setLocale(form: FormData): Promise<void> {
  const locale = form.get("locale");
  if (!isLocale(locale)) {
    return;
  }
  (await cookies()).set(LOCALE_COOKIE, locale, {
    path: "/",
    maxAge: ONE_YEAR_SECONDS,
    sameSite: "lax",
    secure: serverConfig().appUrl.protocol === "https:",
  });
  revalidatePath("/", "layout");
}
