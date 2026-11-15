# Architecture Decision Records

Every architectural decision that is hard to reverse, or that changes what `docs/architecture/` says, has its own
ADR. Numbering is continuous and never reuses a number.

Statuses: `Proposed` → `Accepted` (after the owner accepts it in a PR) → optionally `Superseded by ADR-XXXX`.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-stack-versions.md) | Backend stack versions | Accepted |
| [0002](0002-spring-ai-tool-calling.md) | The tool loop belongs to the runtime, not to Spring AI | Accepted |
| [0003](0003-module-convention.md) | Backend module convention | Accepted |
| [0004](0004-tenancy-and-store-scope.md) | Tenancy model and trusted store scope | Accepted with corrections |
| [0005](0005-data-access.md) | Data access through JdbcClient and explicit SQL | Accepted |
| [0006](0006-frontend-and-session.md) | Next.js frontend with a server-side session (BFF) and a client generated from the OpenAPI contract | Accepted |
| [0007](0007-woocommerce-connection.md) | WooCommerce connection: secrets, authentication and SSRF protection | Accepted |
| [0008](0008-woocommerce-import-and-stock-semantics.md) | WooCommerce import (14 days) and stock semantics | Accepted |
| [0009](0009-woocommerce-webhooks-inbox-outbox.md) | WooCommerce webhooks: registration, signature, inbox, normalization and outbox | Accepted |
| [0010](0010-event-dispatch-polling-reconciliation.md) | Event dispatch to Temporal, WooCommerce polling and reconciliation, Integration Health | Accepted |
| [0011](0011-evidence-snapshot-risk-detection-opportunity.md) | Evidence, ContextSnapshot, risk detector and the Opportunity episode | Accepted |
| [0012](0012-decision-proposal-v1-policy-evaluation.md) | DecisionProposal v1, policy evaluation and InventoryGuardianWorkflow in shadow mode | Accepted |
| [0013](0013-ui-languages.md) | Interface languages (English default, Polish second) | Accepted |
| [0014](0014-decision-and-activity-read-api.md) | Decision and activity read API | Accepted |
| [0015](0015-woocommerce-order-history-import.md) | WooCommerce order history import (12 months) in the background | Accepted |
| [0016](0016-agent-runtime-model-gateway-tools-knowledge.md) | Agent runtime, ModelGateway, read tools, knowledge registry and the agent proposer | Proposed |
| [0017](0017-meta-ads-adapter.md) | Meta Ads adapter: connection, ad state polling and the pause write | Proposed |
| [0018](0018-store-business-profile-and-onboarding.md) | Store Business Profile versions and onboarding progress | Proposed |
| [0019](0019-sales-and-product-analytics.md) | Sales and product analytics v1: definitions, coverage and the MetricDTO | Proposed |

Template: [0000-template.md](0000-template.md).
