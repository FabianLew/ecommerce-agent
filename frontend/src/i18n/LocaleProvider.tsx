"use client";

import { createContext, type ReactNode, useContext } from "react";

import { DEFAULT_LOCALE, type Locale } from "./locale";
import { type Messages, messagesFor } from "./messages";

const LocaleContext = createContext<Locale>(DEFAULT_LOCALE);

/** Passes the locale chosen on the server to client components; they read the catalog themselves. */
export function LocaleProvider({ locale, children }: { locale: Locale; children: ReactNode }) {
  return <LocaleContext.Provider value={locale}>{children}</LocaleContext.Provider>;
}

export function useLocale(): Locale {
  return useContext(LocaleContext);
}

export function useMessages(): Messages {
  return messagesFor(useLocale());
}
