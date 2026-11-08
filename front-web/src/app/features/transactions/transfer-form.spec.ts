import { FormControl } from '@angular/forms';

import { amountValidator, normalizeAmount } from './transfer-form';

describe('amountValidator', () => {
  const check = (value: string, currency = 'EUR') =>
    amountValidator(() => currency)(new FormControl(value, { nonNullable: true }));

  it.each(['1', '10.5', '25.50', '0.01', '25,50'])('accepts %s', (value) => {
    expect(check(value)).toBeNull();
  });

  it.each(['abc', '-5', '1e5', '1.2.3', '.5', '5.'])('rejects %s', (value) => {
    expect(check(value)?.['amount']).toContain('Enter an amount');
  });

  it.each(['0', '0.00', '0,0'])('rejects the zero amount %s', (value) => {
    expect(check(value)?.['amount']).toContain('greater than zero');
  });

  it('enforces the decimals of the currency', () => {
    expect(check('1.234', 'EUR')?.['amount']).toContain('at most 2');
    expect(check('1.2345678', 'BTC')).toBeNull();
    expect(check('1.123456789', 'BTC')?.['amount']).toContain('at most 8');
  });

  it('leaves an empty value to Validators.required', () => {
    expect(check('')).toBeNull();
  });
});

describe('normalizeAmount', () => {
  it('turns a decimal comma into a point and trims', () => {
    expect(normalizeAmount(' 25,50 ')).toBe('25.50');
  });
});
