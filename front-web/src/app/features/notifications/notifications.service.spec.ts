import { Account } from '../accounts/accounts-api';
import { Transfer } from '../transactions/transactions-api';
import { toNotifications } from './notifications.service';

const accounts = [
  { accountId: 'MINE-1', userId: 'me' },
  { accountId: 'MINE-2', userId: 'me' },
  { accountId: 'THEIRS', userId: 'other' },
] as Account[];

function transfer(sender: string, receiver: string, status = 'DONE'): Transfer {
  return {
    transactionId: `${sender}-${receiver}-${status}`,
    senderAccountId: sender,
    receiverAccountId: receiver,
    amount: 10,
    currency: 'EUR',
    reference: null,
    status,
    failureReason: null,
    createdAt: '2026-11-08T10:00:00Z',
  } as Transfer;
}

const convert = (t: Transfer) => toNotifications([t], accounts, 'me');

describe('toNotifications', () => {
  it('names money coming in, with its sender', () => {
    expect(convert(transfer('EXT', 'MINE-1'))[0]).toMatchObject({
      kind: 'received',
      counterparty: 'EXT',
    });
  });

  it('names money going out, with its receiver', () => {
    expect(convert(transfer('MINE-1', 'EXT'))[0]).toMatchObject({
      kind: 'sent',
      counterparty: 'EXT',
    });
  });

  it('names a move between own accounts', () => {
    expect(convert(transfer('MINE-1', 'MINE-2'))[0]).toMatchObject({
      kind: 'internal',
      counterparty: null,
    });
  });

  it('reports a cancelled transfer as cancelled', () => {
    expect(convert(transfer('MINE-1', 'EXT', 'CANCELLED'))[0].kind).toBe('cancelled');
  });

  it('skips pending transfers', () => {
    expect(convert(transfer('MINE-1', 'EXT', 'PENDING'))).toEqual([]);
  });

  it('skips transfers of other users, which an admin can also see', () => {
    expect(convert(transfer('THEIRS', 'EXT'))).toEqual([]);
  });
});
