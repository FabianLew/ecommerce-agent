import { redirect } from "next/navigation";

import { StatePanel } from "@/components/StatePanel";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

export const dynamic = "force-dynamic";

/** Entry point: open the first store the user can access. There is no store creation wizard (04 §2). */
export default async function Home() {
  const api = await backend("/");
  const { data, response } = await api.GET("/api/v1/stores");
  redirectIfUnauthenticated(response.status, "/");
  if (!data) {
    throw new Error(`Stores request failed with ${response.status}`);
  }
  const first = data.items[0];
  if (first) {
    redirect(storePath(first.storeId));
  }
  return (
    <main className="centered">
      <StatePanel
        tone="info"
        title="Nie masz jeszcze dostępu do żadnego sklepu"
        description="Poproś właściciela sklepu o dodanie Cię do zespołu. Po nadaniu dostępu odśwież stronę."
      >
        <form action="/auth/logout" method="post">
          <button type="submit" className="button secondary">Wyloguj się</button>
        </form>
      </StatePanel>
    </main>
  );
}
