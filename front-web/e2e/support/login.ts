import { expect, Page } from '@playwright/test';

import { PASSWORD, TestUser } from './stack';
import { totp } from './totp';

/** TOTP secrets of the users whose authenticator the tests set up, by email. */
const secrets = new Map<string, string>();

/**
 * Signs in through the real Keycloak login page (Authorization Code + PKCE), like a person would.
 * ADVISOR and ADMIN must use TOTP: the first time, the test sets up the authenticator from the
 * secret Keycloak shows, then answers with codes computed from it.
 */
export async function signIn(page: Page, user: TestUser): Promise<void> {
  await page.goto('/auth');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.locator('#username').fill(user.email);
  await page.locator('#password').fill(PASSWORD);
  await page.locator('#kc-login').click();

  if (
    await page
      .locator('#kc-totp-secret-key, #mode-manual')
      .first()
      .isVisible({ timeout: 3_000 })
      .catch(() => false)
  ) {
    await setUpTotp(page, user);
  } else if (
    await page
      .locator('#otp')
      .isVisible({ timeout: 1_000 })
      .catch(() => false)
  ) {
    await page.locator('#otp').fill(totp(secrets.get(user.email)!));
    await page.locator('#kc-login').click();
  }
  await expect(page).toHaveURL(/\/account$/);
}

async function setUpTotp(page: Page, user: TestUser): Promise<void> {
  const manual = page.locator('#mode-manual');
  if (await manual.isVisible().catch(() => false)) {
    await manual.click();
  }
  const secret = (await page.locator('#kc-totp-secret-key').innerText()).trim();
  secrets.set(user.email, secret);
  await page.locator('#totp').fill(totp(secret));
  await page.locator('#saveTOTPBtn, input[type="submit"], button[type="submit"]').first().click();
}
