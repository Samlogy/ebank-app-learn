import { expect, test } from '@playwright/test';

import { signIn } from './support/login';
import { createUser, PASSWORD } from './support/stack';

// The realm's browser flow (ebank-browser): TOTP is required for ADMIN and ADVISOR, optional for
// customers. The chat test covers ADVISOR; these cover the other two.

test('a customer without an authenticator signs in with the password only', async ({ page }) => {
  const customer = await createUser('customer');
  await page.goto('/auth');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.locator('#username').fill(customer.email);
  await page.locator('#password').fill(PASSWORD);
  await page.locator('#kc-login').click();

  // Straight to the app: no OTP setup, no OTP prompt.
  await expect(page).toHaveURL(/\/account$/);
});

test('an admin must set up TOTP on the first sign-in, then enter a code', async ({ browser }) => {
  const admin = await createUser('admin', 'ADMIN');

  const first = await browser.newPage();
  await first.goto('/auth');
  await first.getByRole('button', { name: 'Sign in' }).click();
  await first.locator('#username').fill(admin.email);
  await first.locator('#password').fill(PASSWORD);
  await first.locator('#kc-login').click();
  await expect(first.locator('#kc-totp-secret-key, #mode-manual').first()).toBeVisible();
  await first.close();

  // signIn sets up the authenticator from the shown secret, then a fresh session asks for a code.
  const page = await browser.newPage();
  await signIn(page, admin);
  const again = await browser.newPage();
  await again.goto('/auth');
  await again.getByRole('button', { name: 'Sign in' }).click();
  await again.locator('#username').fill(admin.email);
  await again.locator('#password').fill(PASSWORD);
  await again.locator('#kc-login').click();
  await expect(again.locator('#otp')).toBeVisible();
});
