import { Pipe, PipeTransform } from '@angular/core';

const CRYPTO_DECIMALS = 8;
const CRYPTO = new Set(['BTC', 'ETH']);

/** Formats with the device's locale. */
@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(amount: number | null | undefined, currency: string): string {
    if (amount === null || amount === undefined) {
      return '—';
    }
    return new Intl.NumberFormat(navigator.language, {
      style: 'currency',
      currency,
      ...(CRYPTO.has(currency) ? { maximumFractionDigits: CRYPTO_DECIMALS } : {}),
    }).format(amount);
  }
}

/** The most decimals a currency allows. The backend enforces the same limits. */
export function currencyDecimals(currency: string): number {
  return CRYPTO.has(currency)
    ? CRYPTO_DECIMALS
    : (new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions()
        .maximumFractionDigits ?? 2);
}
