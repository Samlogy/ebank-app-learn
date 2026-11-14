import { ChangeDetectionStrategy, Component, computed, inject, OnInit } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { NotificationsService } from '../../features/notifications/notifications.service';
import { Icon, IconName } from '../../shared/components/icon';
import { Logo } from '../../shared/components/logo';
import { ThemePicker } from '../../shared/components/theme-picker';
import { AuthService } from '../auth/auth.service';

interface NavItem {
  path: string;
  label: string;
  icon: IconName;
}

const MAX_BADGE = 9;

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icon, Logo, ThemePicker],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationsService);

  private readonly customerNav: NavItem[] = [
    { path: '/account', label: 'Accounts', icon: 'wallet' },
    { path: '/transactions', label: 'Transactions', icon: 'transfer' },
    { path: '/analytics', label: 'Analytics', icon: 'chart' },
    { path: '/chatbot', label: 'Chatbot', icon: 'chat' },
  ];

  /** Advisors answer customers: their console comes first. They have no banking data of their own to show. */
  protected readonly nav = computed<NavItem[]>(() =>
    this.auth.isAdvisor()
      ? [{ path: '/advisor', label: 'Advisor', icon: 'chat' }, ...this.customerNav]
      : this.customerNav,
  );

  protected readonly unread = this.notifications.unreadCount;
  protected readonly unreadLabel = computed(() =>
    this.unread() > MAX_BADGE ? `${MAX_BADGE}+` : `${this.unread()}`,
  );

  ngOnInit(): void {
    this.notifications.start();
  }

  protected closeMenu(menu: HTMLElement): void {
    menu.hidePopover();
  }
}
