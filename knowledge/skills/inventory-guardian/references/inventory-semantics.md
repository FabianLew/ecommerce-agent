# Inventory semantics

- `availableToSell` is the stock the store can sell now; reservations are already accounted for (ADR-0008).
- Units sold are counted over the last 7 full days in the store's time zone; velocity is units per day over that
  window; days of cover is available stock divided by velocity.
- Quality `COMPLETE` means the order history covers the whole window. Any other quality, or an unavailable value,
  means the forecast must not be used as a basis for a pause.
- Stock is current when it was observed or confirmed within the stock freshness limit before the snapshot's `asOf`.
- A replenishment is known only when a confirmed delivery exists. In the pilot there is no supplier integration,
  so the plan is usually unknown; say so in the uncertainties.
