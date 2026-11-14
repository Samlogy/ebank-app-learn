import { computed, effect, inject, Injectable, signal } from '@angular/core';
import { Subscription } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { NotificationDto, NotificationsApi, NotificationType } from './notifications-api';

export type NotificationKind = 'received' | 'sent' | 'internal' | 'cancelled' | 'advisor';

export interface AppNotification {
  id: string;
  kind: NotificationKind;
  amount: number | null;
  currency: string | null;
  /** The other account, when there is one. */
  counterparty: string | null;
  /** The transfer, or the conversation for an advisor message. */
  sourceId: string | null;
  createdAt: string;
  read: boolean;
}

export const PAGE_SIZE = 20;

const KINDS: Record<NotificationType, NotificationKind> = {
  TRANSFER_RECEIVED: 'received',
  TRANSFER_SENT: 'sent',
  TRANSFER_INTERNAL: 'internal',
  TRANSFER_CANCELLED: 'cancelled',
  ADVISOR_MESSAGE: 'advisor',
};

/**
 * Notifications are stored by the backend: read state follows the user across browsers.
 * New ones are pushed live over the shared STOMP connection; after a reconnect the list and the
 * unread count are reloaded, since a push may have been missed.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly auth = inject(AuthService);
  private readonly api = inject(NotificationsApi);
  private readonly realtime = inject(RealtimeService);

  private live?: Subscription;
  private readonly unread = signal(0);

  readonly items = signal<AppNotification[]>([]);
  readonly page = signal(0);
  readonly totalPages = signal(0);
  readonly loaded = signal(false);
  readonly failed = signal(false);
  readonly unreadCount = this.unread.asReadonly();
  readonly hasUnread = computed(() => this.unread() > 0);

  constructor() {
    effect(() => {
      if (!this.auth.user()) {
        this.stop();
      }
    });
  }

  /** Called once by the shell: live updates and the badge for the whole session. */
  start(): void {
    if (this.live) {
      return;
    }
    this.live = this.realtime
      .watch<NotificationDto>('/user/queue/notifications')
      .subscribe((dto) => this.received(toNotification(dto)));
    this.live.add(this.realtime.reconnected$.subscribe(() => this.refresh()));
    this.refreshCount();
  }

  refresh(page = this.page()): void {
    this.api.list(page, PAGE_SIZE).subscribe({
      next: (result) => {
        this.items.set(result.items.map(toNotification));
        this.page.set(result.page);
        this.totalPages.set(result.totalPages);
        this.failed.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.failed.set(true);
        this.loaded.set(true);
      },
    });
    this.refreshCount();
  }

  markRead(id: string): void {
    const item = this.items().find((n) => n.id === id);
    if (!item || item.read) {
      return;
    }
    this.setRead(id, true);
    this.unread.update((n) => Math.max(0, n - 1));
    this.api.markRead(id).subscribe({
      error: () => {
        this.setRead(id, false);
        this.refreshCount();
      },
    });
  }

  markAllRead(): void {
    const before = this.items();
    this.items.update((list) => list.map((n) => ({ ...n, read: true })));
    this.unread.set(0);
    this.api.markAllRead().subscribe({
      error: () => {
        this.items.set(before);
        this.refreshCount();
      },
    });
  }

  private received(notification: AppNotification): void {
    if (this.items().some((n) => n.id === notification.id)) {
      return;
    }
    // Only the first page shows the newest ones; other pages keep their position.
    if (this.page() === 0 && this.loaded()) {
      this.items.update((list) => [notification, ...list].slice(0, PAGE_SIZE));
    }
    if (!notification.read) {
      this.unread.update((n) => n + 1);
    }
  }

  private refreshCount(): void {
    this.api
      .unreadCount()
      .subscribe({ next: ({ count }) => this.unread.set(count), error: () => undefined });
  }

  private setRead(id: string, read: boolean): void {
    this.items.update((list) => list.map((n) => (n.id === id ? { ...n, read } : n)));
  }

  private stop(): void {
    this.live?.unsubscribe();
    this.live = undefined;
    this.items.set([]);
    this.unread.set(0);
    this.loaded.set(false);
  }
}

export function toNotification(dto: NotificationDto): AppNotification {
  return {
    id: dto.id,
    kind: KINDS[dto.type] ?? 'received',
    amount: dto.amount,
    currency: dto.currency,
    counterparty: dto.counterparty,
    sourceId: dto.transactionId,
    createdAt: dto.createdAt,
    read: dto.read,
  };
}
