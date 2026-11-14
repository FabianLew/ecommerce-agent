import Link from "next/link";

import { StatePanel } from "@/components/StatePanel";

export const dynamic = "force-dynamic";

const ERRORS: Record<string, string> = {
  expired: "Logowanie trwało zbyt długo albo zostało rozpoczęte w innej karcie. Spróbuj ponownie.",
  rejected: "Nie udało się potwierdzić logowania. Spróbuj ponownie.",
};

export default async function SignedOut({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const { error } = await searchParams;
  const message = error ? ERRORS[error] : undefined;
  return (
    <main className="centered">
      <StatePanel
        tone={message ? "error" : "info"}
        title={message ? "Logowanie nie powiodło się" : "Wylogowano"}
        description={message ?? "Sesja została zakończona na tym urządzeniu."}
      >
        <Link href="/auth/login" className="button primary" prefetch={false}>Zaloguj się</Link>
      </StatePanel>
    </main>
  );
}
