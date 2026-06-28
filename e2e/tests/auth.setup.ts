import { test as setup, expect } from '@playwright/test';
import path from 'node:path';

export const STORAGE_STATE = path.join(__dirname, '..', 'playwright', '.auth', 'user.json');

/**
 * Bootstraps a fresh test account for the chromium project and persists its
 * authenticated session to STORAGE_STATE. Runs once per Playwright invocation
 * (it's the only test in the setup project).
 *
 * Signup auto-logs the user in (see SignupController.signup -> redirect:/app),
 * so we don't need a separate /login step.
 */
setup('create test user + persist session', async ({ page }) => {
  const email = `e2e-user-${Date.now()}@test.local`;
  const password = 'E2eTestPassword!23';

  await page.goto('/signup');
  await page.getByRole('textbox', { name: /email/i }).fill(email);
  await page.getByLabel(/hasło/i).fill(password);
  await page.getByRole('button', { name: /załóż konto/i }).click();

  await page.waitForURL('**/app');
  await expect(page).toHaveURL(/\/app$/);

  await page.context().storageState({ path: STORAGE_STATE });
});
