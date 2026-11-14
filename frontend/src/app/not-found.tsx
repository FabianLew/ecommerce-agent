import Link from "next/link";

import { StatePanel } from "@/components/StatePanel";

export default function NotFound() {
  return (
    <main className="centered">
      <StatePanel tone="warning" title="Nie znaleziono strony" description="Ten adres nie istnieje albo jest niedostępny.">
        <Link href="/" className="button primary">Przejdź do swoich sklepów</Link>
      </StatePanel>
    </main>
  );
}
