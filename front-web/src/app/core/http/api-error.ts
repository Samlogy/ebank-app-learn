import { HttpErrorResponse } from '@angular/common/http';

/** The backend answers every error with an RFC 9457 ProblemDetail. */
export function apiErrorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'The server cannot be reached. Check your connection and try again.';
    }
    const violations = error.error?.violations as { field: string; message: string }[] | undefined;
    if (violations?.length) {
      return violations.map((v) => `${v.field}: ${v.message}`).join(', ');
    }
    if (typeof error.error?.detail === 'string') {
      return error.error.detail;
    }
  }
  return 'Something went wrong. Please try again.';
}
