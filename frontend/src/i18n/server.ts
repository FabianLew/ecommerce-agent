import "server-only";

import { cookies } from "next/headers";

import { type Locale, LOCALE_COOKIE, resolveLocale } from "./locale";
import { type Messages, messagesFor } from "./messages";

/** The user's interface language from the preference cookie; English when there is no valid choice. */
export async function getLocale(): Promise<Locale> {
  return resolveLocale((await cookies()).get(LOCALE_COOKIE)?.value);
}

export async function getMessages(): Promise<Messages> {
  return messagesFor(await getLocale());
}
