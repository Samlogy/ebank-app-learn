import { expect, test } from '@playwright/test';

import { signIn } from './support/login';
import { activeAccount, createUser, deposit } from './support/stack';

test('a customer signs in through Keycloak and sees their funded account, with no CSP violation', async ({
  page,
}) => {
  const violations: string[] = [];
  page.on('console', (message) => {
    if (message.text().includes('Content Security Policy') && !message.text().includes(':8180')) {
      violations.push(message.text());
    }
  });

  const admin = await createUser('admin', 'ADMIN');
  const alice = await createUser('alice');
  const account = await activeAccount(alice, admin);
  await deposit(admin, account, 250);

  await signIn(page, alice);

  const card = page.getByText(account.slice(-4)).first();
  await expect(card).toBeVisible();
  await expect(page.getByText(/250[.,]00/).first()).toBeVisible();

  await page.getByRole('link', { name: 'Analytics' }).first().click();
  await expect(page.getByTestId('analytics-summary')).toContainText(/250[.,]00/);

  expect(violations).toEqual([]);
});
