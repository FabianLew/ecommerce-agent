import type { Metadata } from "next";
import type { ReactNode } from "react";

import { LocaleProvider } from "@/i18n/LocaleProvider";
import { getLocale } from "@/i18n/server";

import "./globals.css";

export const metadata: Metadata = {
  // Working name from document 04 §2; branding is not decided yet (O5).
  title: "Autonomous E-commerce Operator",
  robots: { index: false, follow: false },
};

export default async function RootLayout({ children }: { children: ReactNode }) {
  const locale = await getLocale();
  return (
    <html lang={locale}>
      <body>
        <LocaleProvider locale={locale}>{children}</LocaleProvider>
      </body>
    </html>
  );
}
