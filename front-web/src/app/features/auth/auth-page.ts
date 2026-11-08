import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';
import { Icon } from '../../shared/components/icon';
import { Logo } from '../../shared/components/logo';

/** The forms themselves are Keycloak's: this page starts the login or registration flow. */
@Component({
  selector: 'app-auth-page',
  imports: [Icon, Logo],
  templateUrl: './auth-page.html',
  styleUrl: './auth-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthPage {
  protected readonly auth = inject(AuthService);
  protected readonly redirecting = signal(false);

  protected async start(flow: 'login' | 'register'): Promise<void> {
    this.redirecting.set(true);
    try {
      await (flow === 'login' ? this.auth.login() : this.auth.register());
    } catch {
      this.redirecting.set(false);
    }
  }

  protected retry(): void {
    location.reload();
  }
}
