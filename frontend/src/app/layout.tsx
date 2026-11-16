import type { Metadata } from "next";
import type { ReactNode } from "react";

import { LocaleProvider } from "@/i18n/LocaleProvider";
import { getLocale } from "@/i18n/server";

// Fonts are bundled with the app (ADR-0029): no request to a font CDN, and offline Docker builds work.
import "@fontsource-variable/inter";
import "@fontsource/jetbrains-mono/400.css";
// Cascade layers in this order: tokens, base, layout, components, pages (declared in tokens.css).
import "@/styles/tokens.css";
import "@/styles/base.css";
import "@/styles/layout.css";
import "@/styles/components.css";
import "@/styles/pages.css";

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
