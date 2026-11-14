import { expect, test } from '@playwright/test';

import { signIn } from './support/login';
import { activeAccount, createUser, deposit, transfer } from './support/stack';

test('money received while the app is open shows up live, then can be marked as read', async ({
  page,
}) => {
  const admin = await createUser('admin', 'ADMIN');
  const alice = await createUser('alice');
  const bob = await createUser('bob');
  const aliceAccount = await activeAccount(alice, admin);
  const bobAccount = await activeAccount(bob, admin);
  await deposit(admin, bobAccount, 100);

  await signIn(page, alice);
  const bell = page.getByRole('link', { name: /^Notifications/ }).first();
  await expect(bell).toHaveAccessibleName('Notifications');

  // Pushed over STOMP: no reload, no polling.
  await transfer(bob, bobAccount, aliceAccount, 42.5);
  await expect(bell).toHaveAccessibleName('Notifications, 1 unread');

  await bell.click();
  const item = page.getByRole('list', { name: 'Notifications' }).getByRole('listitem').first();
  await expect(item).toContainText('Money received');
  await expect(item).toContainText(/42[.,]50/);

  await page.getByRole('button', { name: 'Mark all as read' }).click();
  await expect(bell).toHaveAccessibleName('Notifications');
  // Stored on the server: still read after a reload.
  await page.reload();
  await expect(page.getByRole('link', { name: /^Notifications/ }).first()).toHaveAccessibleName(
    'Notifications',
  );
});
