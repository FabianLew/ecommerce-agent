import { getMessages } from "@/i18n/server";

/**
 * Shown inside the shell while a store page loads (plan section 4.G): a skeleton in the shape of a page, no spinner
 * and no fake progress. The navigation stays usable.
 */
export default async function StoreLoading() {
  const t = await getMessages();
  return (
    <div className="skeleton-page" role="status" aria-live="polite" data-testid="page-loading">
      <span className="visually-hidden">{t.shell.loading}</span>
      <div className="skeleton skeleton-heading" aria-hidden="true" />
      <div className="skeleton-row" aria-hidden="true">
        <div className="skeleton skeleton-tile" />
        <div className="skeleton skeleton-tile" />
        <div className="skeleton skeleton-tile" />
      </div>
      <div className="skeleton skeleton-card" aria-hidden="true" />
      <div className="skeleton skeleton-card skeleton-card-short" aria-hidden="true" />
    </div>
  );
}
