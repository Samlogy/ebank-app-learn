import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Icon, IconName } from '../../shared/components/icon';
import { LocalDatePipe } from '../../shared/pipes/local-date.pipe';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { NotificationKind, NotificationsService } from './notifications.service';

const PRESENTATION: Record<NotificationKind, { title: string; icon: IconName; tone: string }> = {
  received: { title: 'Money received', icon: 'plus', tone: 'ok' },
  sent: { title: 'Transfer sent', icon: 'send', tone: '' },
  internal: { title: 'Moved between your accounts', icon: 'transfer', tone: '' },
  cancelled: { title: 'Transfer cancelled', icon: 'close', tone: 'bad' },
};

@Component({
  selector: 'app-notifications-page',
  imports: [RouterLink, Icon, LocalDatePipe, MoneyPipe],
  templateUrl: './notifications-page.html',
  styleUrl: './notifications-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationsPage implements OnInit {
  protected readonly service = inject(NotificationsService);
  protected readonly presentation = PRESENTATION;

  ngOnInit(): void {
    this.service.refresh();
  }
}
