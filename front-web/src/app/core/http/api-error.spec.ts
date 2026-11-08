import { HttpErrorResponse } from '@angular/common/http';

import { apiErrorMessage } from './api-error';

describe('apiErrorMessage', () => {
  it('uses the ProblemDetail detail', () => {
    const error = new HttpErrorResponse({ status: 422, error: { detail: 'Insufficient funds.' } });
    expect(apiErrorMessage(error)).toBe('Insufficient funds.');
  });

  it('lists field violations', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: { detail: 'Invalid.', violations: [{ field: 'amount', message: 'must not be null' }] },
    });
    expect(apiErrorMessage(error)).toBe('amount: must not be null');
  });

  it('explains a network failure', () => {
    expect(apiErrorMessage(new HttpErrorResponse({ status: 0 }))).toContain('cannot be reached');
  });

  it('falls back for anything else', () => {
    expect(apiErrorMessage(new Error('boom'))).toBe('Something went wrong. Please try again.');
  });
});
