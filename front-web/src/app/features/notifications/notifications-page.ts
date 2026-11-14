import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Icon, IconName } from '../../shared/components/icon';
import { Pager } from '../../shared/components/pager';
import { LocalDatePipe } from '../../shared/pipes/local-date.pipe';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { NotificationsApi, Preferences } from './notifications-api';
import { AppNotification, NotificationKind, NotificationsService } from './notifications.service';

const PRESENTATION: Record<NotificationKind, { title: string; icon: IconName; tone: string }> = {
  received: { title: 'Money received', icon: 'plus', tone: 'ok' },
  sent: { title: 'Transfer sent', icon: 'send', tone: '' },
  internal: { title: 'Moved between your accounts', icon: 'transfer', tone: '' },
  cancelled: { title: 'Transfer cancelled', icon: 'close', tone: 'bad' },
  advisor: { title: 'New message from your advisor', icon: 'chat', tone: 'ok' },
};

@Component({
  selector: 'app-notifications-page',
  imports: [RouterLink, Icon, Pager, LocalDatePipe, MoneyPipe],
  templateUrl: './notifications-page.html',
  styleUrl: './notifications-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationsPage implements OnInit {
  protected readonly service = inject(NotificationsService);
  private readonly api = inject(NotificationsApi);
  protected readonly presentation = PRESENTATION;

  protected readonly preferences = signal<Preferences | null>(null);
  protected readonly savingPreferences = signal(false);
  protected readonly preferencesError = signal('');

  ngOnInit(): void {
    this.service.refresh(0);
    this.api.preferences().subscribe({
      next: (preferences) => this.preferences.set(preferences),
      error: () => this.preferencesError.set('Your email and SMS settings could not be loaded.'),
    });
  }

  protected link(item: AppNotification): { path: string; query: Record<string, string> } {
    return item.kind === 'advisor'
      ? { path: '/chatbot', query: {} }
      : { path: '/transactions', query: item.sourceId ? { transactionId: item.sourceId } : {} };
  }

  protected toggle(channel: keyof Preferences, enabled: boolean): void {
    const current = this.preferences();
    if (!current) {
      return;
    }
    const next = { ...current, [channel]: enabled };
    this.preferences.set(next);
    this.savingPreferences.set(true);
    this.preferencesError.set('');
    this.api.savePreferences(next).subscribe({
      next: (saved) => {
        this.preferences.set(saved);
        this.savingPreferences.set(false);
      },
      error: () => {
        this.preferences.set(current);
        this.savingPreferences.set(false);
        this.preferencesError.set('The change could not be saved. Please try again.');
      },
    });
  }
}
