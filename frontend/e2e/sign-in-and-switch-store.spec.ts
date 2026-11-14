import { expect, test, type Page } from "@playwright/test";

const STORE_A = "00000000-0000-4000-8000-00000000000a";
const STORE_B = "00000000-0000-4000-8000-00000000000b";
const STORE_C = "00000000-0000-4000-8000-00000000000c";

async function signIn(page: Page, user: "owner-a" | "owner-b", path = "/") {
  await page.goto(path);
  await expect(page).toHaveURL(/localhost:8180\/realms\/operator\/protocol\/openid-connect\/auth/);
  await page.locator("#username").fill(user);
  await page.locator("#password").fill(user);
  await page.locator("#kc-login").click();
}

test("owner signs in, lands in their store and switches to another store", async ({ page }) => {
  await signIn(page, "owner-a");

  await expect(page).toHaveURL(`/stores/${STORE_A}/dashboard`);
  await expect(page.getByTestId("store-name")).toHaveText("Sklep A");
  await expect(page.getByRole("link", { name: "Overview" })).toHaveAttribute("aria-current", "page");
  await expect(page.getByText("Manage the store")).toBeVisible();

  // Stores the user cannot access are not offered at all.
  const switcher = page.getByLabel("Store", { exact: true });
  await expect(switcher.locator("option")).toHaveText(["Sklep A · Demo", "Sklep C · Demo"]);

  await page.getByRole("link", { name: "Activity" }).click();
  await expect(page).toHaveURL(`/stores/${STORE_A}/activity`);

  await switcher.selectOption(STORE_C);
  // The section is kept, the store changes, and the analyst role has read-only permissions.
  await expect(page).toHaveURL(`/stores/${STORE_C}/activity`);
  await page.getByRole("link", { name: "Overview" }).click();
  await expect(page.getByTestId("store-name")).toHaveText("Sklep C");
  await expect(page.getByText("Read store data")).toBeVisible();
  await expect(page.getByText("Manage the store")).toHaveCount(0);
});

test("a link to a store without access shows a safe state and does not switch stores", async ({ page }) => {
  await signIn(page, "owner-b", `/stores/${STORE_A}/dashboard`);

  await expect(page).toHaveURL(`/stores/${STORE_A}/dashboard`);
  await expect(page.getByRole("heading", { name: "This store is not available" })).toBeVisible();
  await expect(page.getByText("Sklep A")).toHaveCount(0);

  await page.getByRole("link", { name: "Go to your stores" }).click();
  await expect(page).toHaveURL(`/stores/${STORE_B}/dashboard`);
  await expect(page.getByTestId("store-name")).toHaveText("Sklep B");
});

test("tokens stay on the server: the session cookie is HttpOnly and pages carry no JWT", async ({ page, context }) => {
  await signIn(page, "owner-a");
  await expect(page.getByTestId("store-name")).toHaveText("Sklep A");

  const session = (await context.cookies()).find((cookie) => cookie.name === "op_session");
  expect(session?.httpOnly).toBe(true);
  expect(session?.sameSite).toBe("Lax");
  expect(await page.evaluate(() => document.cookie)).not.toContain("op_session");
  // JWTs start with base64url("{\"") = "eyJ"; neither the HTML nor the cookie value is a readable token.
  expect(await page.content()).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\.eyJ/);
  expect(session?.value.split(".")).toHaveLength(5);
  // One cookie must stay below the 4096-byte browser limit (ADR-0006); fail early if tokens grow.
  expect(session?.value.length).toBeLessThan(3500);
});

test("sign-out ends the provider session", async ({ page }) => {
  await signIn(page, "owner-a");
  await expect(page.getByTestId("store-name")).toHaveText("Sklep A");

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL("/signed-out");
  await expect(page.getByRole("heading", { name: "Signed out" })).toBeVisible();

  // Keycloak asks for credentials again: the SSO session was ended, not only our cookie.
  await page.goto("/");
  await expect(page.locator("#username")).toBeVisible();
});

test("cross-site sign-out is rejected", async ({ page }) => {
  await signIn(page, "owner-a");
  const response = await page.request.post("/auth/logout", { headers: { origin: "https://evil.example" }, maxRedirects: 0 });
  expect(response.status()).toBe(403);
});

test("an expiring access token is refreshed transparently", async ({ page, context }) => {
  // The local realm issues 60 s access tokens; the proxy refreshes when less than 30 s remain.
  test.setTimeout(120_000);
  await signIn(page, "owner-a");
  await expect(page.getByTestId("store-name")).toHaveText("Sklep A");
  const before = (await context.cookies()).find((cookie) => cookie.name === "op_session")?.value;

  await page.waitForTimeout(32_000);
  await page.reload();

  await expect(page.getByTestId("store-name")).toHaveText("Sklep A");
  const after = (await context.cookies()).find((cookie) => cookie.name === "op_session")?.value;
  expect(after).toBeDefined();
  expect(after).not.toBe(before);
});

test("owner saves a WooCommerce key and sees the check result; analyst gets a read-only card", async ({ page }) => {
  await signIn(page, "owner-a", `/stores/${STORE_A}/integrations`);
  await expect(page).toHaveURL(`/stores/${STORE_A}/integrations`);
  const card = page.getByTestId("woocommerce-card");
  await expect(card.getByRole("heading", { name: "WooCommerce" })).toBeVisible();

  // Client-side format check: the key and the secret are easy to swap.
  const storeUrl = card.getByLabel("Store address");
  if (await storeUrl.isVisible()) {
    await storeUrl.fill("https://shop.invalid");
  }
  await card.getByLabel("Consumer key").fill("cs_swapped");
  await card.getByLabel("Consumer secret").fill("cs_swapped");
  await card.getByRole("button", { name: /check/ }).last().click();
  await expect(card.getByText("The WooCommerce value starts with ck_.")).toBeVisible();

  // The store address does not resolve: the key is saved, the check reports a repair step.
  await card.getByLabel("Consumer key").fill("ck_e2e0000000000000000000000000000000000001");
  await card.getByLabel("Consumer secret").fill("cs_e2e0000000000000000000000000000000000001");
  await card.getByRole("button", { name: /check/ }).last().click();
  await expect(card.getByTestId("woocommerce-status")).toHaveText("Connection error");
  await expect(card.getByTestId("woocommerce-error")).toContainText("The store could not be reached");
  await expect(card.getByText("…0001")).toBeVisible();
  // Secrets are never rendered back, not even in the form.
  expect(await page.content()).not.toContain("cs_e2e0000000000000000000000000000000000001");

  await page.getByLabel("Store", { exact: true }).selectOption(STORE_C);
  await expect(page).toHaveURL(`/stores/${STORE_C}/integrations`);
  await expect(page.getByRole("heading", { name: "View only" })).toBeVisible();
  await expect(page.getByLabel("Consumer key")).toHaveCount(0);
});

test("the interface is in English by default and the user can switch to Polish and back", async ({ page, context }) => {
  await signIn(page, "owner-a");
  await expect(page.getByTestId("store-name")).toHaveText("Sklep A");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page.getByRole("link", { name: "Overview" })).toBeVisible();

  await page.getByRole("link", { name: "Activity" }).click();
  await expect(page).toHaveURL(`/stores/${STORE_A}/activity`);
  await page.getByRole("button", { name: "Polski" }).click();
  // The page stays the same; only the language changes, and the choice survives a reload.
  await expect(page.getByRole("link", { name: "Aktywność" })).toHaveAttribute("aria-current", "page");
  await expect(page).toHaveURL(`/stores/${STORE_A}/activity`);
  await expect(page.locator("html")).toHaveAttribute("lang", "pl");
  await page.reload();
  await expect(page.getByRole("button", { name: "Wyloguj się" })).toBeVisible();
  expect((await context.cookies()).find((cookie) => cookie.name === "op_locale")?.value).toBe("pl");

  await page.getByRole("button", { name: "English" }).click();
  await expect(page.getByRole("link", { name: "Activity" })).toHaveAttribute("aria-current", "page");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
});

test("decision inbox shows its groups and a positive empty state; activity lists integration events", async ({ page }) => {
  await signIn(page, "owner-a", `/stores/${STORE_A}/decisions`);
  await expect(page).toHaveURL(`/stores/${STORE_A}/decisions`);
  await expect(page.getByRole("heading", { name: "Decisions", exact: true })).toBeVisible();
  await expect(page.getByTestId("decision-group-TO_APPROVE")).toHaveAttribute("aria-current", "page");
  await expect(page.getByRole("heading", { name: "No decisions to approve" })).toBeVisible();
  await page.getByTestId("decision-group-COMPLETED").click();
  await expect(page).toHaveURL(`/stores/${STORE_A}/decisions?group=COMPLETED`);
  await expect(page.getByRole("heading", { name: "No completed decisions yet" })).toBeVisible();

  // An unknown proposal is a safe not-found state, not an error page.
  await page.goto(`/stores/${STORE_A}/decisions/00000000-0000-4000-8000-000000000999`);
  await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();

  // The WooCommerce test above saved a key and ran a check in store A.
  await page.goto(`/stores/${STORE_A}/activity`);
  const list = page.getByTestId("activity-list");
  await expect(list.getByText("The API key was saved").first()).toBeVisible();
  await page.getByRole("link", { name: "Policy", exact: true }).click();
  await expect(page).toHaveURL(`/stores/${STORE_A}/activity?type=POLICY`);
  await expect(page.getByRole("heading", { name: "No activity yet" })).toBeVisible();
});
