import { AbstractControl, ValidationErrors } from '@angular/forms';

import { currencyDecimals } from '../../shared/pipes/money.pipe';

/** Accepts a decimal comma, which many keyboards and locales produce. */
export function normalizeAmount(value: string): string {
  return value.trim().replace(',', '.');
}

export function amountValidator(currency: () => string | undefined) {
  return (control: AbstractControl<string>): ValidationErrors | null => {
    const value = normalizeAmount(control.value ?? '');
    const code = currency();
    if (!value || !code) {
      return null;
    }
    if (!/^\d+(\.\d+)?$/.test(value)) {
      return { amount: 'Enter an amount such as 25.50.' };
    }
    if (/^[0.]+$/.test(value)) {
      return { amount: 'The amount must be greater than zero.' };
    }
    const decimals = currencyDecimals(code);
    if ((value.split('.')[1]?.length ?? 0) > decimals) {
      return { amount: `${code} allows at most ${decimals} decimals.` };
    }
    return null;
  };
}
