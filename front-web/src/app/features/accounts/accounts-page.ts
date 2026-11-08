import { TitleCasePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { catchError, forkJoin, map, of } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { apiErrorMessage } from '../../core/http/api-error';
import { Icon } from '../../shared/components/icon';
import { Pager } from '../../shared/components/pager';
import { LocalDatePipe } from '../../shared/pipes/local-date.pipe';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import {
  Account,
  AccountAction,
  AccountHistoryEntry,
  AccountsApi,
  AccountStatus,
  AccountType,
} from './accounts-api';

const FIAT_CURRENCIES = ['EUR', 'USD', 'GBP', 'CHF'];
const CRYPTO_CURRENCIES = ['BTC', 'ETH'];

const TYPE_LABELS: Record<AccountType, string> = {
  CURRENT: 'Current account',
  SAVING: 'Savings account',
  CRYPTO: 'Crypto wallet',
};

const STATUS_LABELS: Record<AccountStatus, string> = {
  CREATED: 'Awaiting activation',
  ACTIVE: 'Active',
  DISABLED: 'Disabled',
  CLOSED: 'Closed',
};

const CONFIRMATIONS: Record<AccountAction, { title: string; body: string; verb: string }> = {
  activate: {
    title: 'Activate this account?',
    body: 'The account will be able to send and receive money.',
    verb: 'Activate',
  },
  disable: {
    title: 'Disable this account?',
    body: 'It will stop sending and receiving money. Only an administrator can re-enable it.',
    verb: 'Disable',
  },
  close: {
    title: 'Close this account?',
    body: 'Closing is permanent. The balance must be zero.',
    verb: 'Close account',
  },
};

@Component({
  selector: 'app-accounts-page',
  imports: [ReactiveFormsModule, TitleCasePipe, Icon, LocalDatePipe, MoneyPipe, Pager],
  templateUrl: './accounts-page.html',
  styleUrl: './accounts-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccountsPage implements OnInit {
  private readonly api = inject(AccountsApi);
  private readonly auth = inject(AuthService);
  private readonly createDialog = viewChild.required<ElementRef<HTMLDialogElement>>('createDialog');
  private readonly confirmDialog =
    viewChild.required<ElementRef<HTMLDialogElement>>('confirmDialog');
  private readonly historyDialog =
    viewChild.required<ElementRef<HTMLDialogElement>>('historyDialog');

  protected readonly typeLabels = TYPE_LABELS;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly isAdmin = this.auth.isAdmin;

  protected readonly accounts = signal<Account[]>([]);
  protected readonly balances = signal<Record<string, number | null>>({});
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly loading = signal(true);
  protected readonly error = signal('');

  protected readonly form = inject(FormBuilder).nonNullable.group({
    type: 'CURRENT' as AccountType,
    currency: 'EUR',
  });
  protected readonly selectedType = signal<AccountType>('CURRENT');
  protected readonly currencies = computed(() =>
    this.selectedType() === 'CRYPTO' ? CRYPTO_CURRENCIES : FIAT_CURRENCIES,
  );
  protected readonly dialogError = signal('');
  protected readonly saving = signal(false);

  protected readonly pending = signal<{ account: Account; action: AccountAction } | null>(null);
  protected readonly confirmation = computed(() => {
    const pending = this.pending();
    return pending ? CONFIRMATIONS[pending.action] : null;
  });

  protected readonly historyOf = signal<Account | null>(null);
  protected readonly history = signal<AccountHistoryEntry[] | null>(null);

  constructor() {
    this.form.controls.type.valueChanges.subscribe((type) => {
      this.selectedType.set(type);
      this.form.controls.currency.setValue(this.currencies()[0]);
    });
  }

  ngOnInit(): void {
    this.load(0);
  }

  protected load(page: number): void {
    this.loading.set(true);
    this.error.set('');
    this.api.list(page).subscribe({
      next: (result) => {
        this.accounts.set(result.items);
        this.page.set(result.page);
        this.totalPages.set(result.totalPages);
        this.loading.set(false);
        this.loadBalances(result.items);
      },
      error: (error) => {
        this.error.set(apiErrorMessage(error));
        this.loading.set(false);
      },
    });
  }

  protected actionsFor(account: Account): AccountAction[] {
    switch (account.status) {
      case 'CREATED':
        return this.isAdmin() ? ['activate'] : [];
      case 'ACTIVE':
        return ['disable'];
      case 'DISABLED':
        return this.isAdmin() ? ['activate', 'close'] : ['close'];
      case 'CLOSED':
        return [];
    }
  }

  protected actionLabel(action: AccountAction): string {
    return action[0].toUpperCase() + action.slice(1);
  }

  protected openCreate(): void {
    this.dialogError.set('');
    this.form.reset();
    this.createDialog().nativeElement.showModal();
  }

  protected closeCreate(): void {
    this.createDialog().nativeElement.close();
  }

  protected create(): void {
    const { type, currency } = this.form.getRawValue();
    this.saving.set(true);
    this.dialogError.set('');
    this.api.create(type, currency).subscribe({
      next: () => {
        this.saving.set(false);
        this.closeCreate();
        this.load(0);
      },
      error: (error) => {
        this.saving.set(false);
        this.dialogError.set(apiErrorMessage(error));
      },
    });
  }

  protected askConfirm(account: Account, action: AccountAction): void {
    this.dialogError.set('');
    this.pending.set({ account, action });
    this.confirmDialog().nativeElement.showModal();
  }

  protected closeConfirm(): void {
    this.confirmDialog().nativeElement.close();
  }

  protected confirm(): void {
    const pending = this.pending();
    if (!pending) {
      return;
    }
    this.saving.set(true);
    this.dialogError.set('');
    this.api.change(pending.account.accountId, pending.action).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.closeConfirm();
        this.accounts.update((accounts) =>
          accounts.map((a) => (a.accountId === updated.accountId ? updated : a)),
        );
      },
      error: (error) => {
        this.saving.set(false);
        this.dialogError.set(apiErrorMessage(error));
      },
    });
  }

  protected openHistory(account: Account): void {
    this.historyOf.set(account);
    this.history.set(null);
    this.dialogError.set('');
    this.historyDialog().nativeElement.showModal();
    this.api.history(account.accountId).subscribe({
      next: (result) => this.history.set(result.items),
      error: (error) => {
        this.history.set([]);
        this.dialogError.set(apiErrorMessage(error));
      },
    });
  }

  protected closeHistory(): void {
    this.historyDialog().nativeElement.close();
  }

  private loadBalances(accounts: Account[]): void {
    if (accounts.length === 0) {
      this.balances.set({});
      return;
    }
    forkJoin(
      accounts.map((account) =>
        this.api.balance(account.accountId).pipe(
          map((balance) => [account.accountId, balance.amount] as const),
          // One failed balance shows a dash on its own card instead of hiding the whole page.
          catchError(() => of([account.accountId, null] as const)),
        ),
      ),
    ).subscribe((entries) => this.balances.set(Object.fromEntries(entries)));
  }
}
