import { computed, Injectable, signal } from '@angular/core';
import Keycloak from 'keycloak-js';

import { environment } from '../../../environments/environment';

export interface AuthUser {
  id: string;
  name: string;
  email: string;
  emailVerified: boolean;
  roles: string[];
}

/** Seconds of validity a token must still have before it is attached to a request. */
const MIN_TOKEN_VALIDITY = 30;
const SILENT_CHECK_TIMEOUT_MS = 4000;

/**
 * Keycloak owns registration, login and user profiles, so the app only starts those flows.
 * keycloak-js keeps tokens in memory; a page reload restores them from the SSO session.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly keycloak = new Keycloak(environment.keycloak);
  private readonly currentUser = signal<AuthUser | null>(null);
  private recovering = false;

  readonly user = this.currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this.currentUser() !== null);
  readonly isAdmin = computed(() => this.currentUser()?.roles.includes('ADMIN') ?? false);
  /** False when Keycloak could not be reached, so the login page can say so. */
  readonly available = signal(true);

  /** Never rejects: the app must still start, and show the login page, if Keycloak is down. */
  async init(): Promise<void> {
    this.keycloak.onTokenExpired = () => void this.accessToken().catch(() => undefined);
    this.keycloak.onAuthLogout = () => this.currentUser.set(null);
    try {
      await this.keycloak.init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        silentCheckSsoRedirectUri: `${location.origin}/silent-check-sso.html`,
        checkLoginIframe: false,
        messageReceiveTimeout: SILENT_CHECK_TIMEOUT_MS,
      });
      this.currentUser.set(this.readUser());
    } catch {
      this.available.set(false);
    }
  }

  login(): Promise<void> {
    return this.keycloak.login({ redirectUri: this.mainPageUrl() });
  }

  register(): Promise<void> {
    return this.keycloak.register({ redirectUri: this.mainPageUrl() });
  }

  logout(): Promise<void> {
    return this.keycloak.logout({ redirectUri: `${location.origin}/auth` });
  }

  accountConsoleUrl(): string {
    return this.keycloak.createAccountUrl({ redirectUri: location.href });
  }

  /** A token valid for at least the next {@link MIN_TOKEN_VALIDITY} seconds. */
  async accessToken(): Promise<string> {
    try {
      await this.keycloak.updateToken(MIN_TOKEN_VALIDITY);
      return this.keycloak.token!;
    } catch (error) {
      void this.recoverSession();
      throw error;
    }
  }

  /**
   * Refresh tokens are single-use, so another tab can invalidate this tab's token. If the SSO
   * session is still alive this redirect returns straight away with new tokens. If it is not,
   * the app comes back signed out and the guard sends the user to the login page.
   *
   * During a Keycloak or network outage the redirect would replace the app with a browser error
   * page, so the user stays here and the failed request reports the error instead.
   */
  async recoverSession(): Promise<void> {
    if (this.recovering) {
      return;
    }
    this.recovering = true;
    try {
      const { url, realm } = environment.keycloak;
      await fetch(`${url}/realms/${realm}`, { mode: 'no-cors', cache: 'no-store' });
      await this.keycloak.login({ prompt: 'none', redirectUri: location.href });
    } catch {
      this.recovering = false;
    }
  }

  private readUser(): AuthUser | null {
    const token = this.keycloak.tokenParsed;
    if (!this.keycloak.authenticated || !token?.sub) {
      return null;
    }
    const email = (token['email'] as string | undefined) ?? '';
    return {
      id: token.sub,
      name: (token['name'] as string | undefined) || email,
      email,
      emailVerified: token['email_verified'] === true,
      roles: token.realm_access?.roles.filter((role) => role === role.toUpperCase()) ?? [],
    };
  }

  private mainPageUrl(): string {
    return `${location.origin}/account`;
  }
}
