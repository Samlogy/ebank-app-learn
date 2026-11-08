import { currencyDecimals, MoneyPipe } from './money.pipe';

describe('MoneyPipe', () => {
  const pipe = new MoneyPipe();

  it('shows a dash while the amount is unknown', () => {
    expect(pipe.transform(null, 'EUR')).toBe('—');
    expect(pipe.transform(undefined, 'EUR')).toBe('—');
  });

  it('shows zero, which is a real balance', () => {
    expect(pipe.transform(0, 'EUR')).toMatch(/0[.,]00/);
  });

  it('keeps crypto decimals', () => {
    expect(pipe.transform(0.12345678, 'BTC')).toContain('12345678');
  });
});

describe('currencyDecimals', () => {
  it('matches the backend rules', () => {
    expect(currencyDecimals('EUR')).toBe(2);
    expect(currencyDecimals('JPY')).toBe(0);
    expect(currencyDecimals('BTC')).toBe(8);
  });
});
