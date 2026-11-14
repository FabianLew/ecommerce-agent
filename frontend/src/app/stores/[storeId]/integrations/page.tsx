import { notFound } from "next/navigation";

import { PageHeading } from "@/components/PageHeading";
import { StatePanel } from "@/components/StatePanel";
import { backend, redirectIfUnauthenticated } from "@/lib/api";
import { storePath } from "@/lib/store-path";

import { checkConnection } from "./actions";
import { ERROR_HELP, formatInstant, type Integration, STATUS_LABELS, WARNING_HELP } from "./labels";
import { WooCommerceForm } from "./WooCommerceForm";

export const dynamic = "force-dynamic";

/** Integrations (04 §10): WooCommerce card. Secrets are masked and never returned by the backend. */
export default async function IntegrationsPage({ params }: { params: Promise<{ storeId: string }> }) {
  const { storeId } = await params;
  const path = storePath(storeId, "integrations");
  const api = await backend(path);
  const [store, integrations] = await Promise.all([
    api.GET("/api/v1/stores/{storeId}", { params: { path: { storeId } } }),
    api.GET("/api/v1/stores/{storeId}/integrations", { params: { path: { storeId } } }),
  ]);
  redirectIfUnauthenticated(integrations.response.status, path);
  if (!store.data || !integrations.data) {
    notFound();
  }
  const canManage = store.data.permissions.includes("STORE_MANAGE");
  const woo = integrations.data.items.find((item) => item.provider === "WOOCOMMERCE");

  return (
    <>
      <PageHeading>Integracje</PageHeading>
      <section className="card" aria-labelledby="woocommerce-title" data-testid="woocommerce-card">
        <div className="card-header">
          <h2 id="woocommerce-title">WooCommerce</h2>
          {woo ? (
            <span className={`badge badge-${woo.status.toLowerCase()}`} data-testid="woocommerce-status">
              {STATUS_LABELS[woo.status]}
            </span>
          ) : null}
        </div>
        {woo ? <ConnectionDetails integration={woo} timezone={store.data.timezone} /> : (
          <p className="muted">Sklep nie ma jeszcze połączenia. Podłącz WooCommerce kluczem REST API tylko do odczytu.</p>
        )}
        {canManage ? (
          <>
            {woo?.allowedActions.includes("RUN_CHECK") ? (
              <form action={checkConnection.bind(null, storeId, woo.id)} className="inline-form">
                <button type="submit" className="button secondary">Sprawdź połączenie</button>
              </form>
            ) : null}
            <h3 className="subheading">{woo ? "Zmień klucz API" : "Połącz sklep"}</h3>
            <WooCommerceForm storeId={storeId} integrationId={woo?.id} />
          </>
        ) : (
          <StatePanel
            tone="info"
            title="Tylko podgląd"
            description="Połączenia może zmieniać właściciel sklepu."
          />
        )}
      </section>
    </>
  );
}

function ConnectionDetails({ integration, timezone }: { integration: Integration; timezone: string }) {
  return (
    <>
      {integration.lastErrorCode && integration.status === "ERROR" ? (
        <p className="form-message error" role="alert" data-testid="woocommerce-error">
          {ERROR_HELP[integration.lastErrorCode]}
        </p>
      ) : null}
      {integration.warnings.map((warning) => (
        <p key={warning} className="form-message warning">
          {WARNING_HELP[warning]}
        </p>
      ))}
      <dl className="details">
        <dt>Adres sklepu</dt>
        <dd>{integration.storeUrl}</dd>
        <dt>Klucz API</dt>
        <dd>{integration.keyHint ? `…${integration.keyHint}` : "nie zapisano"}</dd>
        <dt>Ostatni test</dt>
        <dd>{formatInstant(integration.lastCheckAt, timezone)}</dd>
        <dt>Ostatnie udane połączenie</dt>
        <dd>{formatInstant(integration.lastSuccessAt, timezone)}</dd>
        <dt>Waluta w WooCommerce</dt>
        <dd>{integration.sourceCurrency ?? "—"}</dd>
      </dl>
    </>
  );
}
