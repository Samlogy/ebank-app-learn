import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';

/** Only API calls get the token, so it can never leak to another origin. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith(`${environment.apiUrl}/`)) {
    return next(request);
  }
  const auth = inject(AuthService);
  return from(auth.accessToken()).pipe(
    switchMap((token) => next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))),
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401) {
        void auth.recoverSession();
      }
      return throwError(() => error);
    }),
  );
};
