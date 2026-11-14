import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = () =>
  inject(AuthService).isAuthenticated() || inject(Router).createUrlTree(['/auth']);

/** Keeps signed-in users away from the login page. */
export const guestGuard: CanActivateFn = () =>
  !inject(AuthService).isAuthenticated() || inject(Router).createUrlTree(['/account']);

/** Pages for one role. Others go back to the main page, which every signed-in user can open. */
export function roleGuard(role: string): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    if (!auth.isAuthenticated()) {
      return inject(Router).createUrlTree(['/auth']);
    }
    return auth.user()!.roles.includes(role) || inject(Router).createUrlTree(['/account']);
  };
}
