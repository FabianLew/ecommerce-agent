# VPS cost estimate: demo and first pilot (ADR-0028)

- Status: draft for the owner's approval. Nothing is bought or deployed without the owner's confirmation.
- Date: 2026-11-15
- Prices: net (excluding VAT), EUR per month. Server prices are Hetzner's official list after the changes of
  15 June 2026, verified by the owner. Other items are estimates to confirm in the Hetzner console before purchase.

| Item | Demo minimum | Demo recommended | Pilot start | Pilot scaled up (only if load tests require it) |
|---|---|---|---|---|
| Server | CX33: 8.49 | CX43: 15.99 | CX43: 15.99 | CCX23: 85.99 |
| Hetzner server backups (+20% of the server price) | 1.70 | 3.20 | 3.20 | 17.20 |
| Public IPv4 (estimate) | 0.5–1 | 0.5–1 | 0.5–1 | 0.5–1 |
| Object Storage in another location (estimate) | 5–6 | 5–6 | 6–8 | 6–8 |
| LUKS-encrypted 50 GB volume for the database (estimate) | 0 | 0 | 2–3 | 2–3 |
| Domain (about 10–15 a year) | 1 | 1 | 1 | 1 |
| External uptime check, dead-man switch, TLS (free plans) | 0 | 0 | 0 | 0 |
| **Total** | **about 17–18** | **about 26–27** | **about 30–34** | **about 110–115** |

A demo and a pilot running at the same time are two servers (about 56–61). The demo is stopped or moved to CX33
when the pilot starts.

## Usage-billed, outside the fixed target

- Temporal Cloud Pay-As-You-Go: actions and storage; a few USD a month expected at demo traffic. Confirm the action
  price and any support fee before creating the account.
- Model provider: the direct Claude API on synthetic data (demo), Amazon Bedrock in the EU for real data (pilot).
  Spending is bounded by ADR-0027, not by the hosting.
- E-mail: SES (about USD 0.10 per 1,000 messages) or another EU SMTP provider; optional.
- Traffic: 20 TB included with the server in the EU.
