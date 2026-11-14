import { expect, test } from '@playwright/test';

import { signIn } from './support/login';
import { activeAccount, createUser, deposit } from './support/stack';

test('a customer asks the assistant, then an advisor takes over and answers live', async ({
  browser,
}) => {
  const admin = await createUser('admin', 'ADMIN');
  const alice = await createUser('alice');
  const advisor = await createUser('advisor', 'ADVISOR');
  await deposit(admin, await activeAccount(alice, admin), 80);

  // The advisor goes online first, so the conversation is assigned to them on its own.
  const advisorContext = await browser.newContext();
  const advisorPage = await advisorContext.newPage();
  await signIn(advisorPage, advisor);
  await advisorPage.goto('/advisor');
  await expect(advisorPage.getByText('Online: new conversations come to you')).toBeVisible();

  const customerContext = await browser.newContext();
  const page = await customerContext.newPage();
  await signIn(page, alice);
  await page.getByRole('link', { name: 'Chatbot' }).first().click();

  const composer = page.getByRole('textbox', { name: 'Message' });
  await composer.fill('What is my balance?');
  await composer.press('Enter');
  const log = page.getByRole('log', { name: 'Conversation' });
  await expect(log.locator('[data-sender="ASSISTANT"]').first()).toContainText(/80[.,]00/);

  await page.getByRole('button', { name: 'Talk to an advisor' }).click();
  await expect(page.getByTestId('chat-status')).toContainText('With an advisor');

  // The advisor sees it under "Mine", with the transcript, and replies.
  await advisorPage.getByTestId('advisor-mine').getByRole('button').first().click();
  await expect(advisorPage.getByRole('log', { name: 'Conversation' })).toContainText(
    'What is my balance?',
  );
  const reply = advisorPage.getByRole('textbox', { name: 'Message' });
  await reply.fill('Hello, I am looking at your account.');
  await reply.press('Enter');

  await expect(log.locator('[data-sender="ADVISOR"]')).toContainText(
    'Hello, I am looking at your account.',
  );
  // The customer had it on screen: the advisor sees it was read.
  await expect(advisorPage.getByText('Seen')).toBeVisible();

  await advisorPage.getByRole('button', { name: 'Resolve' }).click();
  await expect(page.getByTestId('chat-status')).toContainText('Conversation closed');

  await advisorContext.close();
  await customerContext.close();
});
