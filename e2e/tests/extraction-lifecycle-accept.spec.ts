import { test, expect } from '@playwright/test';
import path from 'node:path';

/**
 * Risk anchor: context/foundation/test-plan.md #3 (accepted events flow to /app
 * and the iCal feed; rejected/pending never do), exercised across the browser-only
 * lifecycle from events/from-image.html (XHR upload → fetch polling → redirect on
 * READY) into review.html (ACCEPT/REJECT decisions) and finally /app rendering.
 *
 * Seed reference: tests/seed.spec.ts (role-based locators, wait-for-state, unique
 * data via Date.now(), self-contained cleanup).
 *
 * Boundary contract:
 *   REAL    — Spring Security (storageState), multipart upload, polling JS,
 *             EventReviewService promotion path, JPA + H2 under spring.profiles=e2e,
 *             Thymeleaf rendering of /app.
 *   MOCKED  — ChatModel via StubLlmVisionClient (@Primary under @Profile("e2e"))
 *             returning 3 deterministic events. LLM-quality risk is NOT under
 *             test here — that belongs to LlmExtractionRecordedRegressionTest.
 *
 * Why E2E and not MockMvc: existing tests under §6.6 of test-plan.md cover each
 * layer in isolation (ImageUploadControllerTest, ExtractionServiceTest,
 * EventReviewServiceTest, etc.), but the user-facing sequence — upload, polling,
 * decision form, redirect, render — only exists end-to-end in a real browser.
 * A regression in any cross-layer glue (e.g. a JS polling change that races the
 * server's READY status, or the form's serialization of accept/reject mixed rows)
 * would slip past every MockMvc test.
 */

const STUB_TITLES = {
  spotkanie: 'Spotkanie testowe',
  warsztaty: 'Warsztaty literackie',
  wycieczka: 'Wycieczka do muzeum',
} as const;

// Mirror Spring's #temporals.format(date, 'EEE d MMM yyyy') under en-locale (pinned
// in application-e2e.properties). Computed at JS side so a date-corrupting bug in the
// promotion path surfaces as a string mismatch the assertion can name.
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
function springDate(y: number, m: number, d: number): string {
  const dow = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return `${WEEKDAYS[dow]} ${d} ${MONTHS[m - 1]} ${y}`;
}

// Must match StubLlmVisionClient's hardcoded values exactly — the contract this test
// pins is "what the stub said comes back out unchanged after lifecycle promotion".
const STUB_DATES = {
  spotkanie: springDate(2099, 1, 15),    // "Thu 15 Jan 2099"
  warsztaty: springDate(2099, 1, 20),    // "Tue 20 Jan 2099"
} as const;
const STUB_TIMES = {
  warsztaty: '10:00',                     // spotkanie + wycieczka have null time
} as const;

const SAMPLE_PNG = path.join(__dirname, '..', '..', 'src', 'main', 'resources',
                              'static', 'img', 'logo-mark.png');

test.afterEach(async ({ page }) => {
  // Best-effort: delete any E2E-prefixed events left on /app, in case an
  // assertion above failed before the inline cleanup ran. Auto-accepts the
  // confirm() dialog the row's Usuń button raises.
  page.on('dialog', d => d.accept().catch(() => {}));
  await page.goto('/app');
  while (await page.getByText(/^E2E accepted/).first().isVisible().catch(() => false)) {
    const row = page.getByRole('listitem').filter({ has: page.getByText(/^E2E accepted/) }).first();
    await row.getByRole('button', { name: 'Usuń' }).click();
    await page.waitForLoadState('networkidle');
  }
});

test('accepted events from extraction lifecycle land on /app and survive reload; rejected do not', async ({ page }) => {
  const ts = Date.now();
  const acceptedSpotkanie = `E2E accepted spotkanie ${ts}`;
  const acceptedWarsztaty = `E2E accepted warsztaty ${ts}`;
  const rejectedWycieczka = `E2E rejected wycieczka ${ts}`;

  // Auto-accept any confirm() dialog (the row's Usuń button raises one in app.html).
  // Register once at test start — Playwright keeps page listeners across navigations.
  page.on('dialog', d => d.accept().catch(() => {}));

  // 1. Open the upload page from /app
  await page.goto('/events/from-image');

  // 2. Upload a valid PNG — multipart real, ChatModel stubbed
  await page.getByLabel('Plik').setInputFiles(SAMPLE_PNG);
  await page.getByRole('button', { name: 'Wyślij' }).click();

  // 3. Polling JS in from-image.html redirects to review when the stub resolves
  await page.waitForURL(/\/events\/from-image\/[0-9a-f-]+\/review/, { timeout: 30_000 });

  // 4. Stub returned 3 events — locate rows via the decision fieldset's accessible name
  //    (role=group with legend "Co zrobić z tym wydarzeniem?") rather than CSS classes,
  //    so layout refactors don't break this test as long as the form's semantics hold.
  const rows = page.getByRole('listitem').filter({
    has: page.getByRole('group', { name: 'Co zrobić z tym wydarzeniem?' }),
  });
  await expect(rows).toHaveCount(3);

  // Address rows by their position in the StubLlmVisionClient's return list
  // (deterministic — we own the stub). Filtering by title text would fail because
  // review.html keeps proposed titles in <input> values, not in textContent.
  // Confirm the stub order via the first row's pre-filled title before relying on it.
  const rowSpotkanie = rows.nth(0);
  const rowWarsztaty = rows.nth(1);
  const rowWycieczka = rows.nth(2);

  await expect(rowSpotkanie.getByLabel('Tytuł')).toHaveValue(STUB_TITLES.spotkanie);
  await expect(rowWarsztaty.getByLabel('Tytuł')).toHaveValue(STUB_TITLES.warsztaty);
  await expect(rowWycieczka.getByLabel('Tytuł')).toHaveValue(STUB_TITLES.wycieczka);

  // ACCEPT: edit titles to unique values so the assertion proves THIS run's promotion,
  // not a cumulative residue from a prior run
  await rowSpotkanie.getByLabel('Tytuł').fill(acceptedSpotkanie);
  await rowSpotkanie.getByRole('radio', { name: 'Dodaj do kalendarza' }).check();

  await rowWarsztaty.getByLabel('Tytuł').fill(acceptedWarsztaty);
  await rowWarsztaty.getByRole('radio', { name: 'Dodaj do kalendarza' }).check();

  // REJECT: rename for trace, but the title must NOT appear on /app
  await rowWycieczka.getByLabel('Tytuł').fill(rejectedWycieczka);
  await rowWycieczka.getByRole('radio', { name: 'Odrzuć' }).check();

  await page.getByRole('button', { name: 'Zapisz wybór' }).click();

  // 5. Promotion redirects to /app
  await page.waitForURL('**/app');

  // 6. Risk-tied assertion — for ACCEPT rows the listitem must carry BOTH the title
  //    AND the date from the stub (and for warsztaty also the time). This catches
  //    not only "Event row dropped entirely" but also "Event saved with corrupted
  //    date/time" — e.g. promotion path silently substitutes LocalDate.now() or
  //    drops the LocalTime. Scoped to listitems so flash messages can never satisfy
  //    or false-trigger the assertion.
  const eventRow = (title: string, ...mustContain: string[]) => {
    let row = page.getByRole('listitem').filter({ has: page.getByText(title) });
    for (const piece of mustContain) row = row.filter({ hasText: piece });
    return row;
  };
  await expect(eventRow(acceptedSpotkanie, STUB_DATES.spotkanie)).toHaveCount(1);
  await expect(eventRow(acceptedWarsztaty, STUB_DATES.warsztaty, STUB_TIMES.warsztaty)).toHaveCount(1);
  await expect(eventRow(rejectedWycieczka)).toHaveCount(0);

  // 7. Persistence — the "don't disappear after refresh" half of the risk
  await page.reload();
  await expect(eventRow(acceptedSpotkanie, STUB_DATES.spotkanie)).toHaveCount(1);
  await expect(eventRow(acceptedWarsztaty, STUB_DATES.warsztaty, STUB_TIMES.warsztaty)).toHaveCount(1);
  await expect(eventRow(rejectedWycieczka)).toHaveCount(0);

  // 8. Inline cleanup — keeps /app empty for next run. afterEach catches leftovers.
  for (const title of [acceptedSpotkanie, acceptedWarsztaty]) {
    await eventRow(title).getByRole('button', { name: 'Usuń' }).click();
    await expect(eventRow(title)).toHaveCount(0, { timeout: 10_000 });
  }
});
