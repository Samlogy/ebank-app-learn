import { directionOf, Transfer } from './transactions-api';

const transfer = { senderAccountId: 'A', receiverAccountId: 'B' } as Transfer;

describe('directionOf', () => {
  it.each([
    [['A'], 'out'],
    [['B'], 'in'],
    [['A', 'B'], 'internal'],
    [['C'], 'other'],
  ])('with own accounts %j is %s', (own, expected) => {
    expect(directionOf(transfer, new Set(own))).toBe(expected);
  });
});
