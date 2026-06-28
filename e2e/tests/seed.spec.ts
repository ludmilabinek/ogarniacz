import { test, expect } from '@playwright/test';

/**
 * Seed exemplar — every generated E2E test is modeled on this one.
 *
 * Demonstrates the four patterns from .claude/skills/10x-e2e/references/seed-test-pattern.md:
 *   1. Role-based locators (getByRole / getByLabel) — no CSS, no XPath.
 *   2. Test independence — full setup → action → assertion → cleanup in one block,
 *      authenticated via storageState (see playwright.config.ts), no UI login.
 *   3. Wait for state, not time — toBeVisible / waitForURL, never waitForTimeout.
 *   4. Risk-tied assertion — the test name binds it to a concrete risk from
 *      context/foundation/test-plan.md (here: #3, accepted event visible on /app + iCal feed).
 *
 * The /events/new path is chosen on purpose: it crosses auth → form → JPA → render
 * without depending on the LLM stub or polling, so the seed itself stays stable
 * regardless of upstream changes to the extraction pipeline.
 */
test('manually-added event persists on /app after page reload', async ({ page }) => {
  const title = `Seed event ${Date.now()}`;
  const isoTomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);

  await page.goto('/events/new');
  await page.getByLabel('Data').fill(isoTomorrow);
  await page.getByLabel('Tytuł').fill(title);
  await page.getByRole('button', { name: 'Zapisz wydarzenie' }).click();

  await page.waitForURL('**/app');
  await expect(page.getByText(title)).toBeVisible();

  await page.reload();
  await expect(page.getByText(title)).toBeVisible();
});
