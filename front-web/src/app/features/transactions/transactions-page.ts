import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { apiErrorMessage } from '../../core/http/api-error';
import { Icon } from '../../shared/components/icon';
import { Pager } from '../../shared/components/pager';
import { LocalDatePipe } from '../../shared/pipes/local-date.pipe';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { Account, AccountsApi } from '../accounts/accounts-api';
import { directionOf, TransactionsApi, Transfer, TransferDirection } from './transactions-api';
import { amountValidator, normalizeAmount } from './transfer-form';

const MAX_REFERENCE_LENGTH = 140;
const MAX_ACCOUNT_ID_LENGTH = 34;
const ACCOUNTS_PAGE_SIZE = 100;

@Component({
  selector: 'app-transactions-page',
  imports: [ReactiveFormsModule, Icon, LocalDatePipe, MoneyPipe, Pager],
  templateUrl: './transactions-page.html',
  styleUrl: './transactions-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TransactionsPage implements OnInit {
  private readonly api = inject(TransactionsApi);
  private readonly accountsApi = inject(AccountsApi);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  protected readonly accounts = signal<Account[]>([]);
  protected readonly ownAccounts = computed(() =>
    this.accounts().filter((account) => account.userId === this.auth.user()?.id),
  );
  protected readonly senderOptions = computed(() =>
    this.ownAccounts().filter((account) => account.status === 'ACTIVE'),
  );
  private readonly ownIds = computed(
    () => new Set(this.ownAccounts().map((account) => account.accountId)),
  );

  protected readonly transfers = signal<Transfer[]>([]);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly loading = signal(true);
  protected readonly error = signal('');

  protected readonly filters = this.fb.group({ transactionId: '', accountId: '' });
  protected readonly hasFilters = signal(false);

  protected readonly form = this.fb.group({
    senderAccountId: ['', Validators.required],
    receiverAccountId: ['', [Validators.required, Validators.maxLength(MAX_ACCOUNT_ID_LENGTH)]],
    amount: ['', [Validators.required, amountValidator(() => this.senderCurrency())]],
    reference: ['', Validators.maxLength(MAX_REFERENCE_LENGTH)],
  });
  protected readonly maxReferenceLength = MAX_REFERENCE_LENGTH;
  protected readonly dialogError = signal('');
  protected readonly saving = signal(false);

  /**
   * Stays the same while the form is unchanged, so retrying after a lost response can never
   * send the money twice. Editing the form makes it a new transfer with a new key.
   */
  private idempotencyKey = crypto.randomUUID();

  constructor() {
    this.form.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => (this.idempotencyKey = crypto.randomUUID()));
    this.form.controls.senderAccountId.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.form.controls.amount.updateValueAndValidity());
  }

  ngOnInit(): void {
    this.accountsApi
      .list(0, ACCOUNTS_PAGE_SIZE)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (result) => this.accounts.set(result.items), error: () => undefined });

    // The URL holds the filters, so notifications can link to one transaction.
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.filters.setValue({
        transactionId: params.get('transactionId') ?? '',
        accountId: params.get('accountId') ?? '',
      });
      this.load(0);
    });
  }

  protected load(page: number): void {
    const { transactionId, accountId } = this.filters.getRawValue();
    this.hasFilters.set(!!transactionId || !!accountId);
    this.loading.set(true);
    this.error.set('');
    this.api.search({ transactionId: transactionId.trim(), accountId, page }).subscribe({
      next: (result) => {
        this.transfers.set(result.items);
        this.page.set(result.page);
        this.totalPages.set(result.totalPages);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(apiErrorMessage(error));
        this.loading.set(false);
      },
    });
  }

  protected applyFilters(): void {
    const { transactionId, accountId } = this.filters.getRawValue();
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {
        transactionId: transactionId.trim() || null,
        accountId: accountId || null,
      },
    });
  }

  protected clearFilters(): void {
    this.filters.reset();
    this.applyFilters();
  }

  protected directionOf(transfer: Transfer): TransferDirection {
    return directionOf(transfer, this.ownIds());
  }

  protected counterparty(transfer: Transfer): string {
    switch (this.directionOf(transfer)) {
      case 'in':
        return transfer.senderAccountId;
      case 'out':
        return transfer.receiverAccountId;
      default:
        return `${transfer.senderAccountId} → ${transfer.receiverAccountId}`;
    }
  }

  protected title(transfer: Transfer): string {
    const titles: Record<TransferDirection, string> = {
      in: 'Received',
      out: 'Sent',
      internal: 'Between your accounts',
      other: 'Transfer',
    };
    return titles[this.directionOf(transfer)];
  }

  protected openDialog(): void {
    this.dialogError.set('');
    this.form.reset({ senderAccountId: this.senderOptions()[0]?.accountId ?? '' });
    this.dialog().nativeElement.showModal();
  }

  protected closeDialog(): void {
    this.dialog().nativeElement.close();
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { senderAccountId, receiverAccountId, amount, reference } = this.form.getRawValue();
    this.saving.set(true);
    this.dialogError.set('');
    this.api
      .transfer(
        {
          senderAccountId,
          receiverAccountId: receiverAccountId.trim(),
          amount: normalizeAmount(amount),
          currency: this.senderCurrency()!,
          reference: reference.trim() || undefined,
        },
        this.idempotencyKey,
      )
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.idempotencyKey = crypto.randomUUID();
          this.closeDialog();
          this.load(0);
        },
        error: (error) => {
          this.saving.set(false);
          this.dialogError.set(apiErrorMessage(error));
          // A refused transfer can still be on record as cancelled.
          this.load(this.page());
        },
      });
  }

  protected senderCurrency(): string | undefined {
    const id = this.form?.controls.senderAccountId.value;
    return this.senderOptions().find((account) => account.accountId === id)?.currency;
  }
}
