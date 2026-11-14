import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';

import { authGuard, guestGuard, roleGuard } from './auth.guards';
import { AuthService } from './auth.service';

function run(guard: typeof authGuard, authenticated: boolean) {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: AuthService, useValue: { isAuthenticated: signal(authenticated) } },
    ],
  });
  const result = TestBed.runInInjectionContext(() => guard(null as never, null as never));
  return result instanceof UrlTree ? TestBed.inject(Router).serializeUrl(result) : result;
}

describe('auth guards', () => {
  it('lets a signed-in user into the app', () => {
    expect(run(authGuard, true)).toBe(true);
  });

  it('sends a signed-out user to the auth page', () => {
    expect(run(authGuard, false)).toBe('/auth');
  });

  it('lets a signed-out user see the auth page', () => {
    expect(run(guestGuard, false)).toBe(true);
  });

  it('sends a signed-in user from the auth page to the main page', () => {
    expect(run(guestGuard, true)).toBe('/account');
  });
});

describe('roleGuard', () => {
  function runRole(roles: string[] | null) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: signal(roles !== null),
            user: signal(roles ? { roles } : null),
          },
        },
      ],
    });
    const result = TestBed.runInInjectionContext(() =>
      roleGuard('ADVISOR')(null as never, null as never),
    );
    return result instanceof UrlTree ? TestBed.inject(Router).serializeUrl(result) : result;
  }

  it('lets a user with the role in', () => {
    expect(runRole(['ADVISOR'])).toBe(true);
  });

  it('sends a user without the role to the main page', () => {
    expect(runRole(['CUSTOMER'])).toBe('/account');
  });

  it('sends a signed-out user to the auth page', () => {
    expect(runRole(null)).toBe('/auth');
  });
});
