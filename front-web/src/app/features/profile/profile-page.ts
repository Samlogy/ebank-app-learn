import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';
import { Icon } from '../../shared/components/icon';
import { ThemePicker } from '../../shared/components/theme-picker';

@Component({
  selector: 'app-profile-page',
  imports: [Icon, ThemePicker],
  templateUrl: './profile-page.html',
  styleUrl: './profile-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProfilePage {
  protected readonly auth = inject(AuthService);
  protected readonly accountUrl = this.auth.accountConsoleUrl();
  protected readonly initials = computed(() =>
    (this.auth.user()?.name ?? '')
      .split(/[\s@.]+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0].toUpperCase())
      .join(''),
  );
}
