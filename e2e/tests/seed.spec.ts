import { test, expect } from '@playwright/test';

/**
 * Seed exemplar — every generated E2E test is modeled on this one.
 *
 * Demonstrates the four patterns from .claude/skills/10x-e2e/references/seed-test-pattern.md:
 *   1. Role-based locators (getByRole / getByLabel) — no CSS, no XPath.
 *   2. Test independence — full setup → action → assertion → cleanup in one block,
 *      authenticated via storageState (see playwright.config.ts), no UI login.
 *   3. Wait for state, not time — toBeVisible / waitForURL / toHaveCount, never
 *      waitForTimeout.
 *   4. Unique data + inline cleanup — Date.now() suffix in the title plus an
 *      explicit delete that asserts row removal before the test ends, so a local
 *      reuseExistingServer run does not pile up rows in H2.
 *
 * No risk anchor — this IS the exemplar, not a risk-covering spec. Risk-bound
 * specs (e.g. extraction-lifecycle-accept.spec.ts for the /app half of Risk #3)
 * cite their context/foundation/test-plan.md row in their own docblocks.
 *
 * The /events/new path is chosen on purpose: it crosses auth → form → JPA →
 * render without depending on the LLM stub or polling, so the seed itself stays
 * stable regardless of upstream changes to the extraction pipeline.
 */
test('manually-added event persists on /app after page reload', async ({ page }) => {
  const title = `Seed event ${Date.now()}`;
  const isoTomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 10);

  // Auto-accept the confirm() dialog raised by the Usuń button in app.html.
  page.on('dialog', d => d.accept().catch(() => {}));

  await page.goto('/events/new');
  await page.getByLabel('Data').fill(isoTomorrow);
  await page.getByLabel('Tytuł').fill(title);
  await page.getByRole('button', { name: 'Zapisz wydarzenie' }).click();

  await page.waitForURL('**/app');
  await expect(page.getByText(title)).toBeVisible();

  await page.reload();
  await expect(page.getByText(title)).toBeVisible();

  // Cleanup — delete the seed row so a local reuseExistingServer run does not
  // pile up events in H2 across invocations.
  const row = page.getByRole('listitem').filter({ has: page.getByText(title) });
  await row.getByRole('button', { name: 'Usuń' }).click();
  await expect(row).toHaveCount(0, { timeout: 10_000 });
});
