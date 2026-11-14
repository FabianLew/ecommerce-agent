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
  await expect(page.getByRole("link", { name: "Przegląd" })).toHaveAttribute("aria-current", "page");
  await expect(page.getByText("Zarządzanie sklepem")).toBeVisible();

  // Stores the user cannot access are not offered at all.
  const switcher = page.getByLabel("Sklep");
  await expect(switcher.locator("option")).toHaveText(["Sklep A · Demo", "Sklep C · Demo"]);

  await page.getByRole("link", { name: "Aktywność" }).click();
  await expect(page).toHaveURL(`/stores/${STORE_A}/activity`);

  await switcher.selectOption(STORE_C);
  // The section is kept, the store changes, and the analyst role has read-only permissions.
  await expect(page).toHaveURL(`/stores/${STORE_C}/activity`);
  await page.getByRole("link", { name: "Przegląd" }).click();
  await expect(page.getByTestId("store-name")).toHaveText("Sklep C");
  await expect(page.getByText("Odczyt danych sklepu")).toBeVisible();
  await expect(page.getByText("Zarządzanie sklepem")).toHaveCount(0);
});

test("a link to a store without access shows a safe state and does not switch stores", async ({ page }) => {
  await signIn(page, "owner-b", `/stores/${STORE_A}/dashboard`);

  await expect(page).toHaveURL(`/stores/${STORE_A}/dashboard`);
  await expect(page.getByRole("heading", { name: "Ten sklep jest niedostępny" })).toBeVisible();
  await expect(page.getByText("Sklep A")).toHaveCount(0);

  await page.getByRole("link", { name: "Przejdź do swoich sklepów" }).click();
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

  await page.getByRole("button", { name: "Wyloguj" }).click();
  await expect(page).toHaveURL("/signed-out");
  await expect(page.getByRole("heading", { name: "Wylogowano" })).toBeVisible();

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
