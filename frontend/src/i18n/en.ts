import type { components } from "@/api/schema";

type Integration = components["schemas"]["IntegrationResponse"];
type SyncJob = NonNullable<Integration["lastSync"]>;
type Health = Integration["health"];

/**
 * English messages, the default catalog (ADR-0013). Its shape is the type of every other catalog, so a
 * missing key in another language fails the type check. Maps keyed by backend codes use `satisfies` so
 * a new code in the API contract also fails the type check until it has a text.
 */
export const en = {
  common: {
    signOut: "Sign out",
    signIn: "Sign in",
    goToStores: "Go to your stores",
    tryAgain: "Try again",
    none: "none",
    user: "User",
    language: "Language",
    languageNames: { en: "English", pl: "Polski" },
  },
  shell: {
    skipToContent: "Skip to content",
    timezone: (timezone: string) => `Time zone: ${timezone}`,
    mainNavigation: "Main navigation",
    menu: "Menu",
    store: "Store",
  },
  sections: {
    dashboard: "Overview",
    decisions: "Decisions",
    support: "Customer support",
    analytics: "Analytics",
    automations: "Automations",
    activity: "Activity",
    integrations: "Integrations",
    settings: "Settings",
  },
  home: {
    noStoresTitle: "You do not have access to any store yet",
    noStoresDescription: "Ask the store owner to add you to the team. Refresh the page once access is granted.",
  },
  errorPage: {
    title: "Something went wrong",
    description: (digest: string) => `The page could not be loaded. Try again. Error ID: ${digest}.`,
  },
  notFound: {
    title: "Page not found",
    description: "This address does not exist or is not available.",
  },
  signedOut: {
    signedOutTitle: "Signed out",
    signedOutDescription: "The session on this device has ended.",
    failedTitle: "Sign-in failed",
    errors: {
      expired: "Sign-in took too long or was started in another tab. Try again.",
      rejected: "Sign-in could not be confirmed. Try again.",
    } as Record<string, string>,
  },
  store: {
    unavailableTitle: "This store is not available",
    unavailableDescription: "The store does not exist or you do not have access to it.",
    viewNotReadyTitle: "This view is not available yet",
    viewNotReadyDescription: "It will arrive in a later iteration. Navigation and store selection already work.",
    summary: "Store",
    name: "Name",
    organization: "Organization",
    currency: "Currency",
    timezone: "Time zone",
    roles: "Your roles",
    permissions: "Permissions",
    roleLabels: {
      OWNER: "Owner",
      DECISION_OPERATOR: "Decision operator",
      SUPPORT_OPERATOR: "Customer support operator",
      ANALYST: "Analyst",
    } as Record<string, string>,
    permissionLabels: {
      STORE_READ: "Read store data",
      STORE_MANAGE: "Manage the store",
    } as Record<string, string>,
    monitoringOffTitle: "Store monitoring is not running yet",
    monitoringOffDescription:
      "Monitoring starts once WooCommerce is connected and the data is imported in the Integrations section.",
  },
  integrations: {
    noConnection: "The store has no connection yet. Connect WooCommerce with a read-only REST API key.",
    webhooks: "Webhooks",
    lastWebhook: (at: string) => `Last webhook: ${at}`,
    waitingForWebhook: "Secret saved, waiting for the first webhook",
    webhooksNotConfigured: "Not configured",
    importLast14Days: "Import data from the last 14 days",
    checkConnection: "Check connection",
    changeKey: "Change the API key",
    connectStore: "Connect the store",
    readOnlyTitle: "View only",
    readOnlyDescription: "Only the store owner can change connections.",
    storeUrl: "Store address",
    apiKey: "API key",
    keyNotSaved: "not saved",
    lastCheck: "Last check",
    lastSuccess: "Last successful connection",
    sourceCurrency: "WooCommerce currency",
    health: "Integration health",
    lastPoll: "Last change poll",
    ordersCompleteUntil: "Orders complete until",
    missedWebhooks24h: "Missed webhooks (24 h)",
    notImported: "The data has not been imported yet.",
    sync: "Synchronization",
    orderWindow: "Order range",
    productsVariants: "Products / variants",
    orders: "Orders",
    wooReportsOrders: (count: number) => ` (WooCommerce reports ${count})`,
    finished: "Finished",
    status: {
      PENDING_CREDENTIALS: "Waiting for the API key",
      CHECK_REQUIRED: "Check required",
      CONNECTED: "Connected",
      ERROR: "Connection error",
    } satisfies Record<Integration["status"], string>,
    /** One repair step per check error (ADR-0007, 04 §10). */
    errorHelp: {
      AUTH_FAILED: "WooCommerce rejected the key. Check the consumer key and secret, or create a new key.",
      PERMISSION_DENIED: "The key cannot read products or orders. Create a key with Read permission.",
      UNREACHABLE: "The store could not be reached. Check the address and whether the store is up.",
      TLS_ERROR: "The store's HTTPS certificate is invalid. Fix the certificate with your hosting provider.",
      NOT_WOOCOMMERCE: "There is no WooCommerce API at this address. Check the address and whether pretty permalinks are on.",
      ADDRESS_NOT_ALLOWED: "This address points to a private network and cannot be used.",
      UNEXPECTED_RESPONSE: "The store returned an unexpected response. Try again later.",
    } satisfies Record<NonNullable<Integration["lastErrorCode"]>, string>,
    warningHelp: {
      CURRENCY_MISMATCH:
        "The WooCommerce currency differs from the currency in the store settings. Fix one of them before metrics are built.",
      CURRENCY_UNKNOWN: "The WooCommerce currency could not be read, so it was not compared with the store settings.",
    } satisfies Record<Integration["warnings"][number], string>,
    syncStatus: {
      RUNNING: "Import in progress",
      SUCCEEDED: "Synchronization complete",
      INCOMPLETE: "Import incomplete",
      FAILED: "Import failed",
    } satisfies Record<SyncJob["status"], string>,
    syncIncomplete:
      "WooCommerce reported a different number of orders than were fetched. The data was saved, but metrics will not use this period. Run the import again.",
    syncFailedUnknown: "The import stopped for an unexpected reason. Try again.",
    /** WooCommerce webhook topics as named in the Woo admin (ADR-0009). */
    topics: {
      "order.created": "Order created",
      "order.updated": "Order updated",
      "product.created": "Product created",
      "product.updated": "Product updated",
    } as Record<string, string>,
    healthStatus: {
      NOT_READY: "Not ready",
      STALE: "Data out of date",
      DEGRADED: "Working with warnings",
      HEALTHY: "Working correctly",
    } satisfies Record<Health["status"], string>,
    /** One repair step per reason (ADR-0010). STALE blocks analysis that needs current data. */
    healthReasonHelp: {
      INITIAL_IMPORT_MISSING: "Run the import of the last 14 days.",
      CONNECTION_NOT_CONNECTED: "The connection needs attention. Check the connection or save a new API key.",
      POLL_STALE:
        "Changes could not be read from the store for more than 15 minutes. Check whether the store is up and run a connection check.",
      ORDERS_STALE:
        "Order completeness was last confirmed more than 2 hours ago. Analysis that depends on sales waits for reconciliation.",
      WEBHOOK_SECRET_MISSING:
        "Webhooks are not configured. Changes arrive with a delay of up to 5 minutes. Set up webhooks below.",
      MISSED_WEBHOOKS:
        "Polling the store found changes that no webhook delivered. Check in WooCommerce that the webhooks are active and use the same secret.",
      WEBHOOK_FAILURES: "Some webhooks from the last 24 hours could not be processed. Report this to the support team.",
      EVENT_BACKLOG: "Events have been waiting for processing for more than 5 minutes. Report this to the support team.",
    } satisfies Record<Health["reasons"][number], string>,
    form: {
      storeUrlHint: "The address of the store's home page, e.g. https://shop.example.com",
      consumerKeyHint: "WooCommerce → Settings → Advanced → REST API → Add key, Read permission.",
      checking: "Checking…",
      saveKeyAndCheck: "Save the new key and check",
      connectAndCheck: "Connect and check",
    },
    webhookSetup: {
      intro:
        "Webhooks speed up stock and order updates. Create them manually in WooCommerce → Settings → Advanced → Webhooks: one for each topic, status “Active”, API version “WP REST API Integration v3”, the same delivery URL and the same secret. Without webhooks the periodic synchronization still refreshes the data.",
      deliveryUrl: "Delivery URL",
      deliveryUrlMissing: "The public API address is not configured.",
      topics: "Topics",
      secret: "Webhook secret",
      secretHint:
        "Generate a secret, paste it into the “Secret” field of every webhook in WooCommerce, and save it here. We will not show it again.",
      generate: "Generate a secret",
      saveNew: "Save the new secret",
      save: "Save the secret",
    },
    /** Texts for the backend's validation codes (ADR-0007). */
    fieldMessages: {
      REQUIRED: "This field is required.",
      INVALID_URL: "Enter a valid store address, e.g. https://shop.example.com.",
      INSECURE_SCHEME: "The store address must start with https://.",
      CREDENTIALS_IN_URL: "The store address must not contain a username or password.",
      QUERY_NOT_ALLOWED: "Enter the store address without ? and #.",
      KEY_FORMAT:
        "A WooCommerce value contains only letters, digits and _, and the key starts with ck_, the secret with cs_.",
      CREDENTIALS_MISSING: "Save the API key first.",
    } as Record<string, string>,
    invalidValue: "Invalid value.",
    keyPrefix: (prefix: string) => `The WooCommerce value starts with ${prefix}.`,
    fixFields: "Fix the highlighted fields.",
    connected: "Connected to WooCommerce.",
    keySavedCheckFailed: "The key was saved, but the connection check failed. Details below.",
    noPermission: "You do not have permission to change this store's connections.",
    alreadyConnected: "This store already has a WooCommerce connection. Refresh the page.",
    saveFailed: (requestId: string) => `The connection could not be saved. Try again (request ID ${requestId}).`,
    secretTooShort: "The secret must have at least 16 characters. Generate it with the button.",
    secretSaved: "Secret saved. Webhooks signed with this secret will be accepted.",
    secretInvalid: "Secret: 16–200 characters without spaces.",
    secretSaveFailed: (requestId: string) => `The secret could not be saved (request ID ${requestId}).`,
  },
};

export type Messages = typeof en;
