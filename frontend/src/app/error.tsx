"use client";

import { StatePanel } from "@/components/StatePanel";

export default function ErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <main className="centered">
      <StatePanel
        tone="error"
        title="Coś poszło nie tak"
        description={`Nie udało się wczytać strony. Spróbuj ponownie. Identyfikator błędu: ${error.digest ?? "brak"}.`}
      >
        <button type="button" className="button primary" onClick={reset}>Spróbuj ponownie</button>
      </StatePanel>
    </main>
  );
}
