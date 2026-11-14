# Autonomous E-commerce Operator — MVP UI/UX specification

Version: 1.1 · Date: 14 November 2026 · Status: specification for review

Sources of truth: [01 — product scope and decisions](01-PRODUCT-VISION-AND-ARCHITECTURE.md), [02 — contracts and agent architecture](02-AI-AGENT-ARCHITECTURE.md), [03 — reference Inventory Guardian](03-INVENTORY-GUARDIAN-E2E.md). Their legend **[D] / [U] / [P] / [Z] / [O]** applies. The new product decisions D-04–D-07 are a binding scope; the layouts, components, endpoints and DTOs below are **[P]**. They are not a statement of an existing API or a finished implementation.

Version 1.1 takes D-08/D-09 into account. The Decision Intelligence requirements are added in section 17; the definitions of knowledge, the profile, KPIs and outcomes are described in one place in [05 — Decision Intelligence Architecture](05-DECISION-INTELLIGENCE-ARCHITECTURE.md).

## 1. Goal and UX principles [D, P]

**Simple by default. Powerful by design.** The user must quickly find out how the store is doing, what the AI operator did and what requires a human decision. Full analytics is part of the MVP, available both from the navigation and from a specific decision. We do not create a beginner/expert switch. Differences in the available actions come from permissions and policies, not from the level of analytics knowledge.

Progressive disclosure has three levels:

| Level | Presentation | Source |
|---|---|---|
| 1. What is happening and what to do | A short summary, the key numbers, the proposed action and urgency | Business Summary |
| 2. Why | The rationale, action scope, consequences, risks, freshness and policy | Decision Explanation |
| 3. Check the data | Charts, tables, comparisons, definitions and sources | Analytical Evidence |

The numbers on all levels come from shared evidence and calculations, in line with the [decision representations](02-AI-AGENT-ARCHITECTURE.md#15-decision-representations-for-the-ui-d-p). Expanding must not hide a critical warning: missing data, an uncertain result or the scope of a change are visible before approval. We do not show chain-of-thought. **[D]** The interface is available in two languages, English (the default) and Polish; domain identifiers keep the contract names.

## 2. Navigation, layout and components [P]

### Map of the eight views

All paths below have the `/stores/:storeId` prefix.

| Left sidebar item | Path | Details | Backend module |
|---|---|---|---|
| Overview | `/dashboard` | Problem card, work done | Reporting / Commerce / Support |
| Decisions | `/decisions` | `/decisions/:proposalId` | Intelligence / Automation |
| Customer support | `/support` | `/support/:conversationId` | Customer Support |
| Analytics | `/analytics/:tab` | `sales`, `profitability`, `ads`, `products` and drill-down | Reporting / Commerce |
| Automations | `/automations` | `/automations/:automationId` | Automation |
| Activity | `/activity` | `/activity/:activityId` | Audit / Reporting |
| Integrations | `/integrations` | `/integrations/:integrationId` | Integrations |
| Settings | `/settings/:section` | Store, policies, team, notifications, data | Tenants / Identity / Policies |

Onboarding is a separate path, `/onboarding`, not a ninth everyday view. We do not add a store builder. The sidebar contains the choice of an existing store, eight items, counters of pending decisions and escalations, and the operator state. The counters come from the backend and respect data access.

The header contains breadcrumbs, the store name, the time zone, the period filter on analytics screens and a connection indicator. Operational inbox filters, e.g. "needs a human", stay independent of chart dates. Account settings do not become an additional UI mode.

**Navigation context:** the URL stores the `from`, `to` range (exclusive end), `timezone`, the comparison and the allowed product/campaign and decision source identifiers. E-mail search terms and message content are not put into shareable URLs. Going back restores the tab, filters, pagination and scroll. Switching the store clears the resource identifiers of the previous store; a link to a foreign resource returns a safe "no access" and does not switch the tenant automatically.

### Visual direction and design system

A minimalist, light SaaS: background `#F6F8F7`, surfaces `#FFFFFF`, text `#172B25`, secondary text `#52665E`, accent `#16734B`, a subtle green background `#EAF5EE`, border `#DFE7E2`. Green marks the main action and success, not all positive values. Amber signals attention, red an error; a status also has text and an icon. The palette is a proposal that needs a contrast measurement before implementation.

Typography: Inter or a system fallback, body 16 px/1.5, tables 14 px, secondary labels at least 12 px, page heading 28 px; tabular numbers right-aligned. Spacing scale 4/8/12/16/24/32 px, card radius 12 px. Sidebar about 232 px, a content grid with a 24 px gap. One main action per context; "Approve" describes the scope, e.g. "Approve ad pause". The product name stays Autonomous E-commerce Operator; the name "Commerce OS" from the prototype is not approved branding.

Desktop: a full sidebar and tables; tablet: collapsible navigation and narrower columns; phone: a navigation drawer, cards in one column, a conversation as a separate screen. Tables keep the key fields and a controlled horizontal scroll, and do not lose data by hiding columns without a way to read them. Details with a durable address open as a page; a drawer may serve as a quick preview. On a small screen the Support view goes list → conversation → context.

**React/Next.js components:** `AppShell`, `StoreSwitcher`, `Breadcrumbs`, `DateRangeFilter`, `MetricCard`, `QualityBadge`, `FreshnessIndicator`, `TrendChart`, `DataTable`, `FilterBar`, `DecisionCard`, `ExplanationPanel`, `EvidenceDrawer`, `ExecutionTimeline`, `ConversationList`, `MessageTimeline`, `ReplyComposer`, `HandoffPanel`, `AutonomyControl`, `PolicyPreview`, `IntegrationStatusCard`, `StatePanel`, `ConfirmActionDialog`. This is a shared UI library; the workflow state stays on the backend. Cache keys include the store, permissions, filters and the data version.

## 3. Shared frontend–backend contracts [P]

### Transport conventions

In the endpoint tables, `B = /api/v1/stores/{storeId}`. The tenant comes from the authenticated principal, and the backend checks access to the store and to every resource. Integration secrets never come back in DTOs. List pagination uses `cursor`, `limit` and returns `items`, `nextCursor`, `hasMore` and separately computed counters. Sorting and filters are allowlisted; the frontend does not send SQL.

Every read response contains `meta`: `requestId`, `generatedAt`, `asOf`, `timezone`, `dataVersion`, `completeness`, `warnings`; parts of the data may have their own sources and times. Editable resources return a `version`/ETag. The `allowedActions` field is for presentation, but the server authorizes the command again.

Commands send an `Idempotency-Key`; state-dependent operations also send `If-Match` with the current version. Sending a key a second time with a different payload is a conflict. A `202` response returns `operationId`, `statusUrl` and possibly `actionId`; the UI reads `B/operations/{operationId}` until there is a result or an escalation deadline. `200/201` confirms that the given resource was written; it does not imply an external effect. We do not optimistically mark a mutation as executed.

| HTTP / domain code | UI reaction |
|---|---|
| 401 / no session | Sign in again, keeping a safe navigation context |
| 403 / no permission | An explanation of the restriction, without suggesting a retry that grants rights |
| 404 | Resource unavailable; without revealing foreign identifiers |
| 409 or 412 / `VERSION_CONFLICT`, `OWNERSHIP_CHANGED` | Refresh and show the change; no automatic retry of the command |
| 410 / `PROPOSAL_EXPIRED` | The approval button is inactive; a new analysis can be requested |
| 422 / `POLICY_DENIED`, validation | A clear reason next to the field or action; no client-side workaround |
| 429 / limit | Information about waiting according to retry-after |
| 503 / source unavailable | Historical data with a staleness marker and a block on dependent mutations |

An error has the structure `{code, message, fieldErrors, retryable, requestId, currentVersion}`; optional fields appear only when they apply. A toast is not the only place where an important error is shown. The backend returns a status and a safe description, not raw provider exceptions.

### Shared DTOs

| DTO | Minimal fields and meaning |
|---|---|
| `MetricDTO` | `key`, `value` or `null`, `unit`, `currency`, `quality`, `period`, `definitionVersion`, `sourceRefs`, `observedAt`, `missingComponents`, `comparison` |
| `ComparisonDTO` | The base period value, the absolute and percentage difference; with a zero base, percentage `null` with a reason |
| `AnalysisContext` | Store, dates, time zone, product/campaign filters, comparison, `sourceProposalId`, optional `snapshotId` |
| `DecisionPresentation` | `proposalId`, `revision`, `status`, `businessSummary`, `explanation`, `evidenceRefs`, `analysisLinks`, `policyEvaluation`, `expiresAt`, `execution`, `allowedActions`, ETag |
| `OperationDTO` | ID, type, `PENDING/RUNNING/SUCCEEDED/FAILED/UNKNOWN`, resource reference, domain status, reason and the next allowed action |

`quality` takes values from the [product data model](01-PRODUCT-VISION-AND-ARCHITECTURE.md#6-business-data-model-u-p). `UNAVAILABLE` means `value=null`, never zero. Amounts and time rules follow that model's contract. Metric definitions, not the LLM, determine denominators, qualifying orders and allocations. The operational success of a command is distinguished from the business state: `SUCCEEDED` for a takeover does not mean a reply was sent, and acceptance of a message by the provider does not mean it was read.

### Shared states and updates

Every view has the loading/empty/error/success states described below; in addition `stale`, partial data, no permission and an action in progress. Loading uses a skeleton of the target layout. Empty distinguishes "no events" from "not synchronized yet". Success means a result from a confirmed read. With a partial failure, correct sections stay, and broken ones are marked locally.

In the MVP we propose polling of open operations and conversations with backoff; a refresh does not overwrite a locally edited draft. **O-15:** SSE as a later optimization or a pilot requirement after measuring delays. Every update mechanism keeps versioning. Background refreshes do not reload tables without keeping the selection and focus.

## 4. Dashboard

**Goal and scenarios:** a quick assessment of the store's condition, the work done by the AI and the exceptions that require a human. The user opens a decision, an escalation or a KPI and moves to the right context.

**Layout and data:** a header with the period and comparison; four cards: net sales, orders, Meta spend, contribution margin after known costs. Then the sales trend, "Needs attention", "What the operator did", a Support block (cases handled by the AI / escalations), integration health and data quality. The profitability card has a visible estimate/missing costs label. Every Support counter has a defined window and unit: number of cases, not interchangeably number of messages.

**Actions and navigation:** clicking a KPI → the matching Analytics tab with the same period; a risk card → Decision Detail; an escalation → Conversation Detail; a source error → the specific integration; a recent action → Activity detail. The Dashboard has no bulk "approve all" and no hidden enabling of L2.

**States:** loading — independent card skeletons; empty — no data/events after configuration, a CTA to integrations or a date change; error — a section error and retry, the other sections stay; success — current numbers and executed operations; stale — a "Data from…" bar and the impact on the availability of analyses. "No cases need attention" is a positive empty state, not an error.

**Backend and API:** Reporting aggregates data from the shared read models, without starting new LLM analyses when the page is opened.

| Endpoint | Input → result |
|---|---|
| `GET B/dashboard` | `from,to,timezone,compare` → `metrics: MetricDTO[]`, `salesTrend`, `attentionItems`, `recentActivities`, `supportSummary`, `integrationHealth`, meta |

`attentionItems` contain a type, a resource ID, a simple title, urgency, status and a backend link; not full PII or e-mail content. The metrics and the chart come from the same data version, or the UI explicitly shows the difference in their read time.

## 5. Decision Inbox + Decision Detail

**Goal and scenarios:** organize business decisions, understand the reason and the scope, check the data, approve/reject a valid revision, track execution. Customer conversations have a separate Support queue; a related business decision may exist here.

**List layout and data:** the tabs "To approve", "Monitoring", "Completed"; filters for domain, priority, automation, dates and status. A row/card: the problem, the target, the recommendation, priority, age, deadline, autonomy level and status. "Monitoring" groups, among others, waiting for data; UI groups are a projection, not new workflow states.

**Detail:** breadcrumbs and a simple header; the proposed action and the exact resource; visible validity and risk; a "Why" panel; evidence and sources; a "Detailed analysis" link; rules and approval; an execution and verification timeline. Expandable technical data has request/action IDs for diagnosis. Impact amounts are marked as estimates, provided a method of computing them actually exists.

**Actions:** approve a specific revision, reject with an optional reason, request a new analysis on expiry. Before approval we show the scope and the consequences. The backend repeats the policy check; the UI does not add `approved=true`. L2 shows the execution state and does not force an artificial approval. No button to retry a mutation on UNKNOWN.

**Navigation:** Summary → Explanation → Evidence/Analytics, passing `sourceProposalId`, the product, the campaign and the period. A historical snapshot is labeled "Data used for the decision"; the current analysis has its own `asOf`. Going back keeps the revision and the inbox filters.

**States:** loading — the list and the detail sections load separately; empty — zero decisions in the filter, or everything handled; error — missing detail, an evidence error or a version conflict with a visible refresh; success — "Approval saved", later a separate "Action confirmed"; stale/expired — a warning and a block on approval; pending — a disabled CTA and a visible operation timeline.

**Backend and API:** Intelligence provides the presentation, Automation handles the commands and the link to Temporal.

| Endpoint | Input → result |
|---|---|
| `GET B/decisions` | Filters, cursor → proposal summaries and group counters |
| `GET B/decisions/{proposalId}` | Optional `revision` → `DecisionPresentation`, ETag |
| `GET B/decisions/{proposalId}/evidence` | `revision,cursor` → evidence, snapshot and definitions; scope check |
| `POST B/decisions/{proposalId}/approvals` | `{revision}`, If-Match → 202, approval ID and operation ID |
| `POST B/decisions/{proposalId}/rejections` | `{revision,reason?}`, If-Match → a persisted rejection and a new status |
| `POST B/decisions/{proposalId}/reanalysis` | `{revision}`, If-Match → 202; a new analysis/revision without overwriting the old one |

Commands use the shared idempotency. Expiry, no permission and a conflict return the codes from section 3. The interface does not promise cancellation of an already started action.

## 6. Customer Support + Conversation Detail

**Goal and scenarios:** see the history of the AI's work, find cases that need a human, understand an escalation, take over a conversation, fix a draft and reply. The process, the domain model and the policies come from the [Customer Support Agent](02-AI-AGENT-ARCHITECTURE.md#14-customer-support-agent-d-p); the UI does not implement a second set of rules.

**List layout:** counters of all conversations, cases handled by the AI and cases needing intervention; filters for status, assignment, priority, mailbox and last activity. Searching messages/e-mails requires Support permissions and does not publish customer data in the URL. A row: the customer or a safe identifier, the subject, a preview of the last message, status, assignee, waiting time and an escalation marker. The waiting time is counted from the customer's last message that needs a reply, not from any system activity.

**Conversation Detail layout:** on desktop, three areas — the list, the history and the context. The top of the history shows the status, the owner and "Take over conversation". Messages have an author, a time, an AI/human/system source and a send status. The right panel: summary, escalation reason, verified customer and orders, fact sources, proposed reply. The bottom: a composer with the draft content, the revision history, save information and a send button. Message HTML is sanitized, external images are not loaded automatically by default; we do not render active code.

**Presented data:** `Conversation.status`, priority, assignee, `ownershipVersion`, the thread version, linked orders and the degree of relation verification, the Message timeline, the draft revision, uncertainties and escalation reason codes. Statuses visible to the user: "New", "Handled by AI", "Needs a human", "Taken over", "Resolved". Message statuses stay separate: draft, queued, sending, accepted by the provider, delivered — if we have a confirmation — error or undetermined result. We do not call ACCEPTED "read" or "delivered".

**Actions:** take over/assign a case, edit the draft, prepare an AI draft on request, send the exact revision, mark as resolved, explicitly hand back to the AI. Takeover and sending have separate confirmations. The composer blocks sending without ownership, without permissions or with a stale thread. A new customer message does not overwrite the operator's text: it shows a banner, invalidates the automatic sending of the old draft and requires a review. With a takeover during dispatch we see "You are taking over the conversation; an earlier message is already being sent" and its state. The UI does not promise to recall a sent e-mail.

**Navigation:** an order opens a limited drawer with Commerce facts and possibly a link to the existing system; a new order management screen is not required. An escalation may link a related business decision, but its correspondence stays in Support. From Activity we return to the specific message; from the Dashboard we open the right queue. Customer data is not available to an analyst without Support permissions.

**States:** loading — a list and history skeleton without a scroll jump; empty — no messages, a filter without results, or an unconnected mailbox with a CTA; error — a synchronization/send error on the specific message, with the option to keep the draft; success — a confirmed takeover/draft save and a separate send acceptance; UNKNOWN — the message "Checking whether the message was sent", without an automatic resend. Stale shows the last synchronization; an owner conflict blocks sending and refreshes the context. Leaving the view with an unsaved draft requires a clear choice about keeping the changes.

**Backend and API:** the Customer Support read models return data with appropriate minimization; sending and draft generation are asynchronous operations.

| Endpoint | Input → result |
|---|---|
| `GET B/support/conversations` | Filters, cursor → list items, counters for the same filter, meta |
| `POST B/support/conversation-search` | `{query,filters,cursor}` → permitted results; the query content is not logged as a regular URL |
| `GET B/support/conversations/{id}` | → status, context, escalation, draft summary, ownership/version, allowedActions, ETag |
| `GET B/support/conversations/{id}/messages` | `beforeCursor/afterCursor,limit` → Message DTOs with author/source/send state; a chronological timeline |
| `POST B/support/conversations/{id}/takeover` | `{assigneeId?}`, If-Match → a new ownershipVersion and status; a durable takeover |
| `POST B/support/conversations/{id}/release` | `{reason?}`, If-Match → hand back to the AI, a new version and an admissibility evaluation |
| `POST B/support/conversations/{id}/draft-generation` | `{triggerMessageId}`, If-Match → 202 + operation ID; the result as a new draft |
| `PATCH B/support/drafts/{draftId}` | `{bodyText}`, If-Match of the draft → a new draftRevision, contentHash and ETag |
| `POST B/support/conversations/{id}/send` | `{draftId,draftRevision,triggerMessageId,ownershipVersion}`, If-Match of the thread → 202 + message/operation ID |
| `POST B/support/conversations/{id}/resolve` | `{resolutionCode}`, If-Match → a new state and an audit record |

All commands use the idempotency from section 3. `send` resolves the recipient on the backend and checks the current state. The UI does not send an arbitrary `to`, `cc` or order data as an authorization. The Message DTO contains the ID, direction, authorType, safe content, dates, provider reference, status and failure reason, not the provider token. **O-11–O-13:** the provider, identity rules, retention and attachment handling stay open; the UI shows an attachment as not analyzed until the system has a supported path.

## 7. Analytics: Sales, Profitability, Ads, Products

**Goal and scenarios:** independent analysis of results and verification of recommendations. The user compares periods, examines a campaign or a SKU, checks sources and missing costs. All four tabs are in the MVP; they do not require an expert mode.

**Shared layout:** a date bar, comparison, time zone and active filters; KPI cards; a trend chart; a drill-down table; expandable definitions and sources. Filters have a reset and visible chips. Clicking a series/row narrows the analysis, and breadcrumbs allow going back. Sorting, pagination and aggregation happen in the backend. For large sets we do not load all orders into the browser.

| Tab | Data and detail layout | Actions and drill-down |
|---|---|---|
| Sales | Net sales, qualifying orders, AOV, refunds/returns, trends and comparisons | Day → orders in a limited drill-down; product → Products; the AOV definition and the period base |
| Profitability | Net sales, COGS, fulfillment, fees, ads, known variable costs; a cost waterfall, margin and cost data coverage | Expand a cost component, allocations and gaps; a link to completing costs in Settings for permitted users |
| Ads | Meta spend, platform conversions, ROAS/CPA, a campaign table and trend, attribution model/window and data delay | Campaign → adset → ad; a comparison and a link to the linked SKU only with a known mapping |
| Products | SKU sales and margin, stock, sales velocity, cover, deliveries and forecast uncertainty | Variant → sales/stock chart → linked ads/decisions and historical actions |

The MVP covers analyses and fixed sets of comparisons, not an arbitrary reporting language. We replace "profit" with the precise name contribution margin after known costs; the definition is not repeated here — the [financial model](01-PRODUCT-VISION-AND-ARCHITECTURE.md#6-business-data-model-u-p) applies. AOV/ROAS/CPA have explicit definitions and denominators; zero conversions do not produce an infinite CPA. Different currencies are shown separately until an explicit conversion with a rate and a date is implemented. Meta metrics are not called confirmed attribution to the store's unique orders.

**Data and quality:** every card and table exposes a MetricDTO, cost coverage and the synchronization date. Estimated has a badge with a reason; Unavailable shows "No data" and the missing components. We do not connect a missing value with a line suggesting zero. A variant in Decision Detail may be historical; "Open current analysis" explicitly switches from the snapshot to the current state.

**Actions and navigation:** change the period, comparison, granularity and filter; open the source/definition; go to the related decision, Activity or Integrations. No direct pausing of ads from the table that bypasses the workflow. Analytics export remains question O-14; it is not required for the basic MVP drill-down.

**States:** loading — cards and chart with skeletons, an active date context; empty — no data for the filter, without fictitious zeros; error — a query error local to the tab/table; success — the same query/data version for related numbers; stale — source watermarks and a delay warning; partial — sales available with missing profitability, with a clear reason.

**Backend and API:** Reporting uses versioned definitions and Commerce aggregates. Queries do not run LLM reasoning. All endpoints take `from,to,timezone,compare,granularity` and allowlisted filters; a shared `queryId/dataVersion` allows linking views of the same result.

| Endpoint | Result and additional context |
|---|---|
| `GET B/analytics/sales` | Metrics, series, breakdowns, definitions and meta |
| `GET B/analytics/profitability` | Metrics, costBreakdown, costCoverage, allocationMethods, missingComponents |
| `GET B/analytics/ads` | Metrics and series; `campaignId/adsetId/adId`, attributionSettings |
| `GET B/analytics/products` | Metrics, series, paginated SKU rows; `productVariantId`, forecastMethod and risk flags |
| `GET B/analytics/{tab}/rows` | `queryId`, a whitelisted dimension, cursor, sort → drill-down of the same version; after the query expires, an explicit refresh |
| `GET B/analytics/definitions/{key}` | Definition version, units, denominator, method and sources; without duplicating the algorithm in the UI |

An analysis result has clear completeness statuses. **O-14:** target volumes and delay limits will decide whether heavy queries need to be asynchronous; the current design requires pagination and range limits and does not promise an arbitrary response time.

## 8. Automations + autonomy configuration

**Goal and scenarios:** understand what the system does in the background, check the ready processes, configure the allowed actions and limits, pause a process. We do not require knowledge of agent configuration.

**Layout and data:** a list of the seven processes from the [MVP scope](01-PRODUCT-VISION-AND-ARCHITECTURE.md#seven-processes-using-the-shared-core), with a user-facing name ("Handling customer messages" instead of the technical profile name), a description, status, last/next run, the L0/L1/L2 scope and the health of dependencies. The detail contains how the process works, the allowed actions, the effective policy, limits, exclusions, history and expandable details.

**Actions:** pause/resume the process, change the allowed level per action and scope, set limits, check the effects of a change. L2 requires showing the specific allowed actions and an explicit confirmation. L3 is marked as outside the MVP and inactive. "Pause automation" stops new actions of this process; it does not remove the integration or the audit and does not undo a sent action. For Support, automatic replies are paused separately; receiving messages and human work stay available, as long as the global policies allow it.

**Navigation:** last run → Activity; required approval → Decision Detail; Support escalations → its queue; a dependency failure → Integrations; global policy → Settings with a way back to the process. The form shows which values are inherited and which are overridden within the allowed scope. The global rule editor lives in Settings, without a second independent source of configuration.

**States:** loading — cards; empty — available processes not configured yet, without a builder for arbitrary workflows; error — a dependency blocks the process, a clear link to the fix; success — the policy is saved with a version and a scope; conflict — a new version needs a review; partial/stale — missing current health blocks increasing autonomy.

**Backend and API:** Automation returns the requested and the effective configuration separately, after applying the parent policies.

| Endpoint | Input → result |
|---|---|
| `GET B/automations` | → process cards, status, dependencies, effectiveAutonomy |
| `GET B/automations/{id}` | → allowed actions, configuration, version, effectivePolicy, reference history |
| `POST B/automations/{id}/policy-preview` | Proposed settings + baseVersion → the permission difference and blocking rules; nothing is saved |
| `PATCH B/automations/{id}/configuration` | Approved scope + If-Match → a new configuration; the server recomputes the policy |
| `POST B/automations/{id}/pause` and `/resume` | If-Match, reason → a persisted state and the impact on active operations |

A preview is not an authorization for a future action; the save and the executor still enforce the current state. Every change is audited and available only to a permitted role.

## 9. Activity History

**Goal and scenarios:** see the work done, find the cause of an error and verify the effect. The view is a readable operational history with a way into the audit.

**Layout and data:** a timeline or table with filters for type (detection, recommendation, action, reply, escalation, retry, verification), process, result, time and actor. A row: a simple description, the resource, time, AI/human/system and the result. The detail contains the pre/post-state, the related proposal/draft, the policy version, the attempt and verification timeline and correlation identifiers. Customer replies in the general history are redacted for roles without Support access.

**Actions and navigation:** filtering, copying a safe diagnostic ID, going to the decision, conversation, integration or contextual analytics. A controlled retry is visible only if the backend returns it as an allowed command of the right resource. There is no universal "undo" or "retry" for every event.

**States:** loading — a timeline skeleton; empty — no activity in the period; error — a page fetch error, keeping the filters; success — a confirmed result and further links; UNKNOWN — a highlighted uncertainty status with an escalation owner; pending — the process is running, it is not a completed success.

**Backend and API:** Audit is a history, not an interface for freely mutating events.

| Endpoint | Input → result |
|---|---|
| `GET B/activities` | Filters, cursor → ActivitySummary[], nextCursor, meta |
| `GET B/activities/{id}` | → timeline, actor, resourceRefs, before/after, outcome, retry history, policy/proposal refs, redacted audit |

E-mail content is exposed by the appropriate Support endpoint with its own access control. A delayed audit projection is marked; it does not remove a confirmed ActionExecution result.

## 10. Integrations

**Goal and scenarios:** connect the existing store's sources, check permissions and freshness, renew access, run a synchronization and safely disconnect a connection.

**Layout and data:** cards for WooCommerce, Meta Ads, the Support e-mail mailbox and optional Slack. Detail: separate statuses for authorization, receiving, sending and synchronization; the last success, watermark, backlog, permission scope, account/mailbox, dependent processes and a readable error. E-mail to customers and the internal report channel are described separately. Secrets are masked and never come back from the backend.

**Actions:** connect, check the connection, renew consent, choose a permitted account/mailbox, synchronize, disconnect after showing the dependencies. Connecting send access does not automatically activate L2 replies. Disconnecting blocks new dependent mutations and does not mean deleting history. A send test, if the chosen provider needs one, requires an explicit test recipient and consent; nothing is sent to customers in the background.

**Navigation:** an error of a specific automation → the detail of the right integration; missing costs → Settings; after connecting, a link to the onboarding progress or to starting an analysis. External authorization flows that are opened return to the same step and report the result.

**States:** loading — health and dependencies separately; empty — the connection does not exist yet; error — token, scope or synchronization with a specific fix step; success — "Connected" and separately "Synchronization completed"; partial — reading works, sending is inactive; stale — the last success and the blocked processes. An OAuth HTTP success alone does not mean the data is ready.

**Backend and API:** Integrations validates accounts, secrets, permissions, callbacks and the store URL; the frontend does not keep tokens in the URL or in persistent application storage.

| Endpoint | Input → result |
|---|---|
| `GET B/integrations` / `GET B/integrations/{id}` | → capabilities, health, account label, freshness, sync jobs, dependencies, allowedActions |
| `POST B/integration-connections` | `{provider,returnPath,storeUrl?}` → connection ID and a safe next step; returnPath from an allowlist |
| `POST B/integration-connections/{id}/credentials` | Secrets only for a connector that needs a form → status without returning the values |
| `POST B/integrations/{id}/checks` | → 202 + operation ID, a read-only access test |
| `POST B/integrations/{id}/sync-jobs` | `{scope}`, no unbounded backfill → 202 + job ID |
| `POST B/integrations/{id}/reauthorization` | → a renewal path, without automatically expanding autonomy |
| `POST B/integrations/{id}/disconnect` | If-Match + reason → a disconnected state and a list of stopped dependencies |

The backend callback binds the authorization request to the right user/store and tests the scope before granting the ready status. **O-11:** the choice of the e-mail provider and the connection method stays open, so we do not impose a specific OAuth/IMAP/SMTP flow as an approved implementation.

## 11. Settings & Policies

**Goal and scenarios:** maintain the store data, roles, global action boundaries, finances, reply rules and internal channels. A permitted administrator can stop mutations and review the effects of changes.

**Layout and data:** the tabs "Store", "Policies", "Team", "Notifications", "Financial data", "Customer support rules". A visible global stop state, policy versions and inherited values. Per-automation configuration links to Automations and uses the same Policy Service. Data: the store's time zone/currency, roles and scope, limits, report recipients, missing costs, approved reply sources and the Support L2 categories.

**Actions:** edit, view the diff and the impact, save a version; assign permissions; complete costs; stop mutations; separately and deliberately restore the ability to execute them. The global kill switch applies to the scope returned by the backend, including sends to customers covered by the block; we do not create a hidden bypass for a human button. The UI shows that an already sent action cannot be undone. Stopping does not delete messages or disable the allowed reads.

**Navigation:** missing COGS in Analytics → the right financial form with the SKU context; a disallowed reply → SupportPolicy; an effective rule → the automations that use it and the change audit. Reading a policy does not grant the right to edit it.

**States:** loading — a form without active saves; empty — no costs/recipients with an explanation of the effect; error — validation next to the fields and a block on an invalid save; success — a new version and its scope of validity; conflict — a diff against a newer version, without a silent overwrite; partial — some data sources not completed, the other settings still available.

**Backend and API:** Tenants/Identity store the configuration and scope, the Policy Service computes the effective policy, and Audit records the change. Working roles **[P]**: owner/policy admin, decision operator, Support operator, read-only analyst; the specific matrix is settled in O-13. These are access roles, not user experience modes.

| Endpoint | Input → result |
|---|---|
| `GET/PATCH B/settings/store` | Name, timezone, currency and agreed settings; If-Match → version |
| `GET B/policies` / `GET B/policies/{id}` | → scope, version, rules and inheritance sources |
| `POST B/policies/{id}/preview` | Proposed configuration/baseVersion → diff and impact, without a state change |
| `POST B/policies/{id}/versions` | Configuration + If-Match → a new effective version and an audit |
| `POST B/mutation-stop` | `{enabled,reason}`, If-Match of the current stop state → a confirmed scope and state; disabling requires a separate approval by a permitted user |
| `GET/PATCH B/settings/notifications` | Internal channels, schedule, recipients; versioning and a test as a separate explicit action |
| `GET/PATCH B/settings/financial-inputs` | Costs with a validity period, source and SKU; the validation and recalculation result |
| `GET B/team` / `PATCH B/team/{memberId}/permissions` | Available roles/scope; changes only within the administrator's permissions, If-Match |

SupportPolicy is an item in the same policy catalog, not a new Policy Engine implementation. A change of the currency or of cost definitions requires information about how history is recalculated; we do not overwrite the historical evidence of decisions.

## 12. Onboarding an existing store [D, P]

**Goal:** connect an existing store and enable monitoring, with the readiness of each domain visible. There is no "Create store" step, no generation of a website, catalog or storefront.

| Step | Layout, data and actions | Completion condition / transition |
|---|---|---|
| 1. Connect WooCommerce | A form for the existing address and a secure connection; store name, time zone, account and a read test | A verified connection and a started synchronization; hosting/access errors shown where they occur |
| 2. Connect tools | Cards for Meta, Support e-mail and optional Slack; choosing a permitted account/mailbox and scopes | Each domain gets its own readiness state. Configuration can be revisited; a missing integration blocks the dependent process, not the whole Dashboard |
| 3. Complete business data | Costs, currency/time zone, stock rules, store policies and mappings; information about gaps | Gaps have a list of effects. Sales analysis works before all costs are completed; Inventory L2 requires confirmed semantics and mappings |
| 4. Start monitoring | A summary of sources, the L0/L1 scope and escalation owners; "Start monitoring" | No automatic L2 for ads and customer replies. Go to the Dashboard with a readiness checklist |

After the first synchronization the operator can separately configure SupportPolicy, test drafts and explicitly enable the allowed L2 replies. Connecting a mailbox for reading does not mean consent to sending. Skipping e-mail during onboarding leaves the Support view available with connection instructions; it does not remove the module from the MVP. A missing Meta account likewise leaves sales analytics working and the ad processes visible but inactive.

**Navigation:** a stepper that saves progress, Back/Next, "Finish later" and a return to the specific integration. Success means actual monitoring readiness for the given scope; a long import has progress and a separate result. Finishing the wizard does not hide an incomplete synchronization.

**States:** loading — a connection test or an import with a stage, without a fake percentage; empty — the source is not connected yet; error — a clear reason and a safe retry; success — a confirmed connection/data or monitoring, separately; partial/stale — a checklist of missing sources and limited features. Cancelling an authorization returns to the same step. Refreshing the page does not reset completed steps or send a test message again.

**Backend and API:** connections use the Integrations endpoints, and settings use Settings; onboarding does not duplicate their logic.

| Endpoint | Input → result |
|---|---|
| `GET B/onboarding` | → steps, readiness per domain, import status, warnings, version and nextStep |
| `PATCH B/onboarding` | `{currentStep,completedAcknowledgements}`, If-Match → progress saved; the backend assesses readiness itself |
| `POST B/onboarding/monitoring-start` | `{requestedScopes}`, If-Match → 202 + monitoring operation, the effective L0/L1 scope and blocks |

Registering an existing store address in the platform is a technical account link, not the creation of an e-commerce store. The backend validates permissions, the URL and the connector scope, and the UI does not accept a "ready" declaration as proof of data access.

## 13. User flows and mapping to domains [P]

| Flow | Views in order | Context kept and result |
|---|---|---|
| Stock risk L1 | Dashboard → Decision Detail → Analytics Products → Detail → approval → Activity | Variant, snapshot and period; approval of a specific revision; the technical pause result. Logic: [Inventory Guardian](03-INVENTORY-GUARDIAN-E2E.md#14-linking-the-process-to-the-ui-p) |
| Executed L2 action | Dashboard / Activity → Decision Detail → Ads | Action, policy, target and time; no artificial approval question after execution |
| E-mail escalation | Dashboard → Support with a filter → Conversation Detail → takeover → edit → send | Conversation, trigger and ownership version; the send status stays visible |
| Margin analysis | Dashboard → Profitability → missing COGS → Settings → Profitability | Dates and SKU; the recalculation is described as a new version, without changing the historical decision |
| Fixing an integration | Warning → Integration Detail → reauthorization/sync → previous view | Account, error and source; a business action requires a new evaluation |
| Increasing autonomy | Automations → policy preview → confirmation → Activity | A specific action scope and a new version; parent restrictions still apply |

The UI uses a shared `AnalysisContext` and correlation identifiers. Decision Inbox handles business decisions, Customer Support conversations, Analytics read models, Activity the audit, and Automations/Settings the same policy module. Components do not communicate directly with LLM, Meta or mail providers. No screen grants the model new tools.

## 14. Accessibility and interaction behavior [P]

All features are available from the keyboard, with a visible focus, semantic labels and a logical order. A drawer/dialog manages focus and returns it to the source; Escape closes a harmless preview but does not discard an unsaved draft without a warning. On a route change, focus moves to the heading; on a data refresh it is not moved.

A chart has a text description and a data table; a tooltip is reachable from the keyboard. Color is not the only carrier of status, and an increase is not always good (e.g. CPA). Contrast target: at least 4.5:1 for regular text and 3:1 for large text and essential interactive elements. Measurements of the chosen palette are required; we do not claim certification based on the specification. 200% zoom does not remove access to actions. Animations are limited and respect the reduced motion preference.

Forms have labels, errors next to the fields and a summary. Send changes are announced through a status region without reading the whole history again. An important error stays in the view even after the toast disappears. Large tables have headers and logical sorting, and virtualization keeps accessibility. Touch targets are at least 44 × 44 px; dense data does not force equally small buttons.

Dates are shown with the time zone name in context; relative time has the exact timestamp in an accessible preview. Currencies and percentages have unambiguous units. The message language describes the situation and the next step, without technical exceptions and without hiding uncertainty.

## 15. UI acceptance criteria [P]

| ID | Acceptance scenario |
|---|---|
| UX-01 | The eight views and onboarding are available according to permissions; no store builder and no expert/beginner mode. |
| UX-02 | Moving Dashboard → decision → analytics → back keeps the store, product, dates, filters and revision. |
| UX-03 | The Summary/Explanation/Evidence numbers for a given snapshot are consistent; current data does not replace historical evidence. |
| UX-04 | The four Analytics tabs have comparisons, drill-down and definitions; a missing cost gives Unavailable/Estimated with a reason, not a false zero. |
| UX-05 | An approval for an expired or changed revision is rejected; a double click does not duplicate the action. |
| UX-06 | UNKNOWN and command acceptance are not presented as a confirmed effect; there is no blind resend/retry. |
| UX-07 | A Support escalation shows the summary, the reason and the draft; a takeover disables new automatic replies according to the backend state. |
| UX-08 | A new e-mail/owner change during editing does not lose the local text and blocks sending a stale draft. |
| UX-09 | ACCEPTED, DELIVERED, BOUNCED and UNKNOWN are distinguished and have sources; the AI/human author is visible. |
| UX-10 | Each of the eight views and onboarding goes through loading, empty, error, success, stale and partial with a useful next step. |
| UX-11 | Renewing an integration does not activate L2 on its own; the kill switch shows its effective scope and does not promise to undo an already sent action. |
| UX-12 | An analyst without Support permissions does not see e-mail content in the Dashboard, Activity, search or through a direct link. |
| UX-13 | The store context and cache do not leak when switching accounts or roles; the backend checks every command. |
| UX-14 | Critical flows can be completed with the keyboard, on a phone and with zoom; a chart has a tabular alternative. |
| UX-15 | Onboarding can be interrupted and resumed; finishing without e-mail/Meta shows the limitations, and monitoring still starts only for the ready scope. |

Verification covers component and API contract tests, browser tests of the paths above, an accessibility check and manual tasks with users of different experience. Mockups and demo data must be clearly marked. These criteria are an acceptance plan, not a report of application tests already performed.

## 16. Open design issues [O]

| ID | To resolve | Impact |
|---|---|---|
| O-11 | Mail provider, Sent synchronization, statuses and send deduplication | Specific connection steps and the confirmations available in Conversation Detail |
| O-12 | Identity rules, L2 categories and approved store knowledge | Reply scope and the presentation of missing data |
| O-13 | Role matrix, escalation SLA, retention and attachments | Queues, permissions, priorities and history visibility |
| O-14 | Data volumes, response time and analytics exports | Pagination, heavy queries and a possible asynchronous export |
| O-15 | Polling or SSE and the acceptable update delay | Notifications about new e-mails, ownership changes and operation state |
| O-16 | Final branding, palette validation and usability tests | Finishing the design system; without changing the screen scope |
| O-17 | The default length of history imported during onboarding | Readiness time, comparison analytics and the mail backlog |

D-04–D-07 are not open questions. Choosing the provider, the visual parameters and the detailed endpoints requires implementation approval, without reopening the presence of analytics and Support in the MVP.

## Scope of the first version of the document

The new document gathers one specification of the navigation, the design system, the eight views, onboarding, UI states and the proposed API contracts. Business definitions, execution policies and the Inventory Guardian logic stay in documents 01–03, which the references point to.

## 17. Business Knowledge & Decision Intelligence addendum [D, P]

We keep the eight views and progressive disclosure. Business knowledge does not need a ninth main screen or a form full of arbitrary KPI thresholds. The delta below extends the existing views and contracts, without repeating the algorithms from [05](05-DECISION-INTELLIGENCE-ARCHITECTURE.md).

| View | Presentation and action additions |
|---|---|
| Dashboard | Show the store's current goal and distinguish "we propose an action", "we are monitoring", "no change needed" and "data is missing". NO_ACTION does not have to occupy the urgent decision queue. |
| Decision Inbox/Detail | A decision kind badge, a short "why" and the impact of the store goal. MONITOR shows what/why/until when we monitor and when the next assessment happens. NO_ACTION shows the reason and the end of the assessment. INSUFFICIENT_DATA points to the specific gap and the next step; it does not suggest a deliberate decision to leave the campaign as is. |
| Analytics and Evidence | For every significant number: the source, period, observedAt, data maturity, quality, estimates/allocations and a link to the definition. From the evidence panel the definition version, the provenance and the reliability limitations can be checked. |
| Activity | A separate technical result and a business observation card with the baseline, follow-up, window, quality and confounders. Present the OBSERVED_ASSOCIATION/INCONCLUSIVE labels as "observed change"/"inconclusive", without an "AI earned this" label based only on before/after. |
| Settings & Policies | A "Store goals and strategy" subsection: choice of profitability/growth/balance, priorities and risk tolerance. Automatically obtained costs and budgets show their source and conflicts; editing creates a profile version, not automatically L2 permissions. |
| Onboarding | In the business data step, ask for the main goal and show the costs/constraints obtained from integrations. Ask only for missing information relevant to the available processes; do not force an unknown margin to be zero. |

**Explaining:** level 1 "We are not changing the budget now, because conversions are still coming in"; level 2 describes the evidence, the strategy and the risk; level 3 shows the right KPIs, attribution windows, sample size and sources. This is a sample message, not a rule that always recommends waiting. A delayed feed, a small sample and missing data have different causes, deadlines and fix paths. The model does not create a new KPI value to use in the text.

**DTO/API — delta [P]:** `DecisionPresentation` adds `decisionKind`, `knowledgeReleaseRef`, `storeProfileVersion`, `contextManifestRef`, `monitorPlan`, `insufficientDataReasons`, `outcomeRefs`. `decisionKind` is a normalized backend projection according to the compatibility rules in document 05, not the frontend guessing from text. MetricDTO is extended with `provenanceRef`, `maturity`, `availableAt`, `reliability`, `limitations`; the existing quality/freshness fields keep their meaning. Profiles, metrics and sources have separate reliability levels — we do not replace them with one "AI confidence".

| Endpoint (`B` as in section 3) | Input → result |
|---|---|
| `GET B/business-profile` | → the versioned profile, approved/proposed values, sources, gaps, conflicts, ETag and allowedActions |
| `POST B/business-profile/preview` | Proposed changes + baseVersion → impact on analyses and dependent decisions; nothing is saved |
| `POST B/business-profile/versions` | Changes + If-Match + Idempotency-Key → a new approved version; no action permissions are granted |
| `GET B/decisions/{proposalId}/outcomes` | `revision,cursor` → plans and observations, baseline/follow-up, maturity, confounders, attributionStrength |
| `GET B/evidence/{evidenceId}/provenance` | → the source and lineage available to the role/tenant, the definition version and limitations; no secrets/unnecessary PII |

The existing financial forms update their source system; the Store Profile references approved, versioned cost data and policies. We do not create a second independent editor of the same amounts. A strategy change requires permission for the profile and an audit; the agent must not choose a new goal on its own after a favorable Outcome.

**Additional states:** during loading, keep the previously approved profile and indicate the refresh; empty — no configured goal or no completed observation window, without a fictitious default accepted by the customer; error/conflict — show the competing sources and the new version; success — a new profile version or a completed observation with its quality, without implying a causal effect. An old observation window may be SUPERSEDED/CENSORED, which does not delete the old record.

**Additional acceptance criteria [P]:** the user distinguishes MONITOR from NO_ACTION and from missing data; the GROWTH goal does not unlock a budget change button; a margin conflict does not disappear after a page refresh; a late conversion result creates a revision of the observation and does not overwrite historical evidence; a source link respects permissions. The new questions about the profile, source quality and outcome evaluation are gathered as O-18–O-23 in document 05.

## Changes in version 1.1

Added the Store Business Profile configuration, the distinction of decision kinds, metric provenance/maturity and the Outcome presentation. Extended the requirements of the existing screens and the DTO/API, keeping the eight views and the "Simple by default. Powerful by design." principle.
