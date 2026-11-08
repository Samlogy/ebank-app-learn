import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';

import { environment } from '../../../environments/environment';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  const auth = { accessToken: vi.fn(), recoverSession: vi.fn() };
  let http: HttpClient;
  let backend: HttpTestingController;

  beforeEach(() => {
    vi.resetAllMocks();
    auth.accessToken.mockResolvedValue('token-1');
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  it('adds the bearer token to API calls', async () => {
    const url = `${environment.apiUrl}/accounts`;
    http.get(url).subscribe();
    await Promise.resolve();
    await Promise.resolve();
    const request = backend.expectOne(url);
    expect(request.request.headers.get('Authorization')).toBe('Bearer token-1');
  });

  it('never sends the token to another origin', () => {
    http.get('https://example.com/data').subscribe();
    const request = backend.expectOne('https://example.com/data');
    expect(request.request.headers.has('Authorization')).toBe(false);
    expect(auth.accessToken).not.toHaveBeenCalled();
  });

  it('does not match a look-alike host that starts with the API url', () => {
    const url = `${environment.apiUrl}.evil.example/steal`;
    http.get(url).subscribe();
    expect(backend.expectOne(url).request.headers.has('Authorization')).toBe(false);
  });

  it('recovers the session when the API answers 401', async () => {
    const url = `${environment.apiUrl}/accounts`;
    http.get(url).subscribe({ error: () => undefined });
    await Promise.resolve();
    await Promise.resolve();
    backend.expectOne(url).flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(auth.recoverSession).toHaveBeenCalledOnce();
  });

  it('leaves other errors to the caller', async () => {
    const url = `${environment.apiUrl}/accounts`;
    http.get(url).subscribe({ error: () => undefined });
    await Promise.resolve();
    await Promise.resolve();
    backend.expectOne(url).flush(null, { status: 403, statusText: 'Forbidden' });
    expect(auth.recoverSession).not.toHaveBeenCalled();
  });
});
