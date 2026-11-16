import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

import { expect, test, type Page } from "@playwright/test";

/**
 * The Inventory Guardian scenario of the plan's section F (document 03 §2), end to end on the full local stack:
 * WooCommerce with the SERUM-30 seed, the API and the worker with infra/e2e/application-e2e.yml (shorter waits,
 * same rules). It needs that stack, so it runs only with E2E_SCENARIO=1 (set in CI).
 */
const STORE_B = "00000000-0000-4000-8000-00000000000b";
const COMPOSE_FILE = fileURLToPath(new URL("../../infra/docker-compose.yml", import.meta.url));

test.skip(!process.env.E2E_SCENARIO, "needs the full compose stack and the worker (E2E_SCENARIO=1)");

/** Runs wp-cli against the local store, in the seed container (which can reach the API on the host). */
function wp(...args: string[]): string {
  return execFileSync(
    "docker",
    ["compose", "-f", COMPOSE_FILE, "run", "--rm", "-T", "--no-deps", "--entrypoint", "wp", "woo-seed",
      "--path=/var/www/html", ...args],
    { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
  ).trim();
}

async function signIn(page: Page, path: string) {
  await page.goto(path);
  await page.locator("#username").fill("owner-b");
  await page.locator("#password").fill("owner-b");
  await page.locator("#kc-login").click();
}

test("a stock drop in WooCommerce becomes a pause proposal waiting for approval in shadow mode", async ({ page }) => {
  test.setTimeout(8 * 60_000);

  // 1. Connect the local store with its read-only key.
  await signIn(page, `/stores/${STORE_B}/integrations`);
  const card = page.getByTestId("woocommerce-card");
  await expect(card.getByTestId("woocommerce-status"), "store B is already connected: the scenario needs a fresh "
    + "stack (docker compose down -v)").toHaveCount(0);
  await card.getByLabel("Store address").fill("http://localhost:8081");
  await card.getByLabel("Consumer key").fill("ck_local_operator_0000000000000000000000");
  await card.getByLabel("Consumer secret").fill("cs_local_operator_0000000000000000000000");
  await card.getByRole("button", { name: /check/ }).last().click();
  await expect(card.getByTestId("woocommerce-status")).toHaveText("Connected", { timeout: 30_000 });

  // 2. Webhooks: the operator saves a secret here and enters it with the delivery URL in WooCommerce.
  await card.getByRole("button", { name: "Generate a secret" }).click();
  const secret = await card.locator("#webhookSecret").inputValue();
  await card.getByRole("button", { name: "Save the secret" }).click();
  await expect(card.getByTestId("webhook-status")).toHaveText("Secret saved, waiting for the first webhook");
  const deliveryUrl = (await card.getByTestId("webhook-url").innerText()).trim();
  expect(deliveryUrl).toMatch(/^http:\/\/host\.docker\.internal:8080\/api\/v1\/webhooks\/woocommerce\//);
  for (const topic of ["order.created", "order.updated", "product.created", "product.updated"]) {
    wp("wc", "webhook", "create", "--user=admin", `--name=Operator ${topic}`, `--topic=${topic}`,
      `--delivery_url=${deliveryUrl}`, `--secret=${secret}`, "--status=active", "--porcelain");
  }

  // 3. Import the last 14 days: 49 SERUM-30 units in the last 7 full days, 60 in stock (not at risk yet).
  await card.getByRole("button", { name: "Import data from the last 14 days" }).click();
  await expect(card.getByTestId("sync-status")).toHaveText("Synchronization complete", { timeout: 120_000 });
  // The 12-month order history follows in the background and does not hold anything up (B-13b).
  await expect(card.getByTestId("sync-history")).toContainText(/Importing in the background|Imported from/);

  // 4. Stock drops to 18 in WooCommerce; Woo delivers the product webhook through Action Scheduler.
  const productId = wp("wc", "product", "list", "--user=admin", "--sku=SERUM", "--field=id");
  const variationId = wp("wc", "product_variation", "list", productId, "--user=admin", "--sku=SERUM-30",
    "--field=id");
  wp("wc", "product_variation", "update", productId, variationId, "--user=admin", "--stock_quantity=18");
  wp("action-scheduler", "run", "--hooks=woocommerce_deliver_webhook_async");
  await expect(async () => {
    await page.reload();
    await expect(card.getByTestId("webhook-status")).toHaveText(/^Last webhook:/, { timeout: 1_000 });
  }).toPass({ timeout: 60_000, intervals: [2_000] });

  // 5. Two risk observations min-gap apart open an episode; the baseline proposes, the policy requires approval.
  await page.goto(`/stores/${STORE_B}/decisions`);
  const list = page.getByTestId("decision-list");
  await expect(async () => {
    await page.reload();
    await expect(list.getByRole("link", { name: /^Low stock: .*\(SERUM-30\)$/ })).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 4 * 60_000, intervals: [5_000] });
  await expect(list.getByTestId("decision-cover")).toHaveText("2.6 days");
  await expect(list.getByTestId("decision-recommendation")).toHaveText(
    "Pause the ad “Serum 30 ml – reklama dedykowana”",
  );

  // 6. Decision Detail: the summary, the reasons with the excluded catalog ad, the policy checks and the evidence.
  await list.getByRole("link", { name: /^Low stock: .*\(SERUM-30\)$/ }).click();
  await expect(page.getByTestId("decision-status")).toHaveText("Waiting");
  await expect(page.getByTestId("decision-cover")).toHaveText("2.6 days");
  const summary = page.getByTestId("decision-summary");
  await expect(summary.getByText("ad-9001")).toBeVisible();
  await expect(summary.getByText("18", { exact: true })).toBeVisible();
  await expect(summary).toContainText("49 sold in the last 7 days");
  // ADR-0025: the first cap a person can fix is shown; monitoring was not started, and the deployment is in shadow
  // mode on top (the backend tests cover each cap).
  await expect(page.getByTestId("approval-blocked")).toContainText("Monitoring has not been started");
  await expect(page.getByRole("button", { name: "Approve" })).toBeDisabled();

  const why = page.getByTestId("decision-why");
  await expect(why.getByText("Catalog ads also promote other products, so they are not paused.")).toBeVisible();
  await expect(page.getByTestId("excluded-ads")).toContainText("Katalog – wszystkie produkty: also promotes other products");
  await expect(page.getByTestId("decision-checks")).toContainText("Requires approval");
  await expect(page.getByTestId("policy-checks").getByRole("row")).toHaveCount(7);

  const evidence = page.getByTestId("decision-evidence");
  await expect(evidence.getByText(/^Data used for the decision, as of /)).toBeVisible();
  await expect(evidence.getByText("Cited by the proposal").first()).toBeVisible();

  // 7. Activity shows the detection, the recommendation and the policy steps of the same episode.
  await page.goto(`/stores/${STORE_B}/activity`);
  const activity = page.getByTestId("activity-list");
  await expect(activity.getByText("A low-stock risk was detected").first()).toBeVisible();
  await expect(activity.getByText("Recommendation ready").first()).toBeVisible();
  await expect(activity.getByText("Safety checks done").first()).toBeVisible();
});
