import { computed, effect, inject, Injectable, signal } from '@angular/core';
import { forkJoin, Subscription, timer } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { Account, AccountsApi } from '../accounts/accounts-api';
import { directionOf, TransactionsApi, Transfer } from '../transactions/transactions-api';

export type NotificationKind = 'received' | 'sent' | 'internal' | 'cancelled';

export interface AppNotification {
  id: string;
  kind: NotificationKind;
  amount: number;
  currency: string;
  /** The other account, when there is one. */
  counterparty: string | null;
  createdAt: string;
  read: boolean;
}

const POLL_INTERVAL_MS = 30_000;
const RECENT_TRANSFERS = 20;
const ACCOUNTS_PAGE_SIZE = 100;

/**
 * The notifications backend does not exist yet, so these come from the user's recent transfers.
 * Read state is per browser. When the module ships, only this service needs to change.
 */
@Injectable({ providedIn: 'root' })
export class NotificationsService {
  private readonly auth = inject(AuthService);
  private readonly accountsApi = inject(AccountsApi);
  private readonly transactionsApi = inject(TransactionsApi);

  private readonly latest = signal<Omit<AppNotification, 'read'>[]>([]);
  private readonly readIds = signal<Set<string>>(new Set());
  private polling?: Subscription;

  readonly loaded = signal(false);
  readonly failed = signal(false);
  readonly items = computed<AppNotification[]>(() =>
    this.latest().map((item) => ({ ...item, read: this.readIds().has(item.id) })),
  );
  readonly unreadCount = computed(() => this.items().filter((item) => !item.read).length);

  constructor() {
    effect(() => {
      const userId = this.auth.user()?.id;
      this.readIds.set(userId ? readStored(userId) : new Set());
    });
  }

  start(): void {
    this.polling ??= timer(0, POLL_INTERVAL_MS).subscribe(() => {
      if (!document.hidden) {
        this.refresh();
      }
    });
  }

  refresh(): void {
    forkJoin({
      accounts: this.accountsApi.list(0, ACCOUNTS_PAGE_SIZE),
      transfers: this.transactionsApi.search({ size: RECENT_TRANSFERS }),
    }).subscribe({
      next: ({ accounts, transfers }) => {
        this.latest.set(toNotifications(transfers.items, accounts.items, this.auth.user()?.id));
        this.failed.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.failed.set(true);
        this.loaded.set(true);
      },
    });
  }

  markRead(id: string): void {
    this.saveRead(new Set([...this.readIds(), id]));
  }

  markAllRead(): void {
    // Only what is listed is kept: older ids can never come back into the recent window.
    this.saveRead(new Set(this.latest().map((item) => item.id)));
  }

  private saveRead(ids: Set<string>): void {
    this.readIds.set(ids);
    const userId = this.auth.user()?.id;
    if (!userId) {
      return;
    }
    try {
      localStorage.setItem(storageKey(userId), JSON.stringify([...ids]));
    } catch {
      // Storage can be blocked. Read state then lasts until the next reload.
    }
  }
}

export function toNotifications(
  transfers: Transfer[],
  accounts: Account[],
  userId: string | undefined,
): Omit<AppNotification, 'read'>[] {
  const own = new Set(accounts.filter((a) => a.userId === userId).map((a) => a.accountId));
  return transfers.flatMap((transfer) => {
    const direction = directionOf(transfer, own);
    if (transfer.status === 'PENDING' || direction === 'other') {
      return [];
    }
    const kind: NotificationKind =
      transfer.status === 'CANCELLED'
        ? 'cancelled'
        : direction === 'in'
          ? 'received'
          : direction === 'out'
            ? 'sent'
            : 'internal';
    return [
      {
        id: transfer.transactionId,
        kind,
        amount: transfer.amount,
        currency: transfer.currency,
        counterparty:
          direction === 'in'
            ? transfer.senderAccountId
            : direction === 'out'
              ? transfer.receiverAccountId
              : null,
        createdAt: transfer.createdAt,
      },
    ];
  });
}

function storageKey(userId: string): string {
  return `ebank.notifications.read.${userId}`;
}

function readStored(userId: string): Set<string> {
  try {
    const ids: unknown = JSON.parse(localStorage.getItem(storageKey(userId)) ?? '[]');
    return new Set(Array.isArray(ids) ? ids.filter((id) => typeof id === 'string') : []);
  } catch {
    return new Set();
  }
}
