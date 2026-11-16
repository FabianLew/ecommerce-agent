import { mkdirSync } from "node:fs";

import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

/**
 * Screenshot matrix and accessibility check for the UI redesign (docs/ui/ui-ux-redesign-plan.md, section 7.1).
 * Runs against the local stack after the Inventory Guardian scenario has filled store B, so it is opt-in:
 * E2E_SCREENS=1 SCREENS_DIR=<folder> pnpm test:e2e e2e/screens.spec.ts
 * Each page is captured at every width; axe must report no serious or critical violation.
 */
const STORE_B = "00000000-0000-4000-8000-00000000000b";
const WIDTHS = (process.env.SCREENS_WIDTHS ?? "390,1024,1440,1920,2560").split(",").map(Number);
const DIR = process.env.SCREENS_DIR ?? "test-results/screens";
const SECTIONS = [
  "dashboard",
  "decisions",
  "decision-detail",
  "onboarding",
  "integrations",
  "ad-mappings",
  "analytics",
  "activity",
  "notifications",
  "settings",
] as const;

test.skip(!process.env.E2E_SCREENS, "needs the local stack with the Inventory Guardian scenario (E2E_SCREENS=1)");

async function signIn(page: Page) {
  await page.goto(`/stores/${STORE_B}/dashboard`);
  if (await page.locator("#username").isVisible()) {
    await page.locator("#username").fill("owner-b");
    await page.locator("#password").fill("owner-b");
    await page.locator("#kc-login").click();
  }
  await expect(page.getByTestId("current-user")).toBeVisible();
}

async function pathOf(page: Page, section: (typeof SECTIONS)[number]): Promise<string> {
  const base = `/stores/${STORE_B}`;
  switch (section) {
    case "decision-detail": {
      await page.goto(`${base}/decisions`);
      const href = await page.getByTestId("decision-list").getByRole("link").first().getAttribute("href");
      return href ?? `${base}/decisions`;
    }
    case "ad-mappings":
      return `${base}/integrations/ads`;
    case "onboarding":
    case "notifications":
      return `${base}/${section}`;
    default:
      return `${base}/${section}`;
  }
}

test("screens at every width, without serious accessibility violations", async ({ page }) => {
  test.setTimeout(10 * 60_000);
  mkdirSync(DIR, { recursive: true });
  await signIn(page);
  const violations: string[] = [];
  for (const section of SECTIONS) {
    const path = await pathOf(page, section);
    for (const width of WIDTHS) {
      await page.setViewportSize({ width, height: width < 600 ? 844 : 1000 });
      await page.goto(path);
      await page.evaluate(() => document.fonts.ready);
      await page.screenshot({ path: `${DIR}/${section}-${width}.png`, fullPage: true });
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect.soft(overflow, `${section} at ${width}px scrolls sideways`).toBeLessThanOrEqual(0);
    }
    const result = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"]).analyze();
    for (const v of result.violations.filter((item) => item.impact === "serious" || item.impact === "critical")) {
      violations.push(`${section}: ${v.id} (${v.impact}) × ${v.nodes.length}: ${v.nodes[0]?.target.join(" ")}`);
    }
  }
  expect(violations, violations.join("\n")).toEqual([]);
});
