import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { forkJoin, of, switchMap } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { LowerCasePipe } from '@angular/common';

import { Icon } from '../../shared/components/icon';
import { LocalDatePipe } from '../../shared/pipes/local-date.pipe';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { Account, AccountsApi } from '../accounts/accounts-api';
import {
  AnalyticsApi,
  bars,
  Counterparties,
  intervalFor,
  lastDays,
  Overview,
  Summary,
  Timeline,
} from './analytics-api';

export const RANGES = [7, 30, 90, 365] as const;
const CHART_WIDTH = 640;
const CHART_HEIGHT = 200;

@Component({
  selector: 'app-analytics-page',
  imports: [Icon, MoneyPipe, LocalDatePipe, LowerCasePipe],
  templateUrl: './analytics-page.html',
  styleUrl: './analytics-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnalyticsPage {
  private readonly api = inject(AnalyticsApi);
  private readonly accountsApi = inject(AccountsApi);
  protected readonly auth = inject(AuthService);

  protected readonly ranges = RANGES;
  protected readonly chartWidth = CHART_WIDTH;
  protected readonly chartHeight = CHART_HEIGHT;

  protected readonly days = signal<number>(30);
  protected readonly accountId = signal('');
  protected readonly currency = signal('');
  protected readonly accounts = signal<Account[]>([]);

  protected readonly summary = signal<Summary | null>(null);
  protected readonly timeline = signal<Timeline | null>(null);
  protected readonly outgoing = signal<Counterparties | null>(null);
  protected readonly incoming = signal<Counterparties | null>(null);
  protected readonly overview = signal<Overview | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal('');

  protected readonly range = computed(() => lastDays(this.days()));
  protected readonly currencies = computed(
    () => this.summary()?.currencies.map((c) => c.currency) ?? [],
  );
  protected readonly selected = computed(
    () => this.summary()?.currencies.find((c) => c.currency === this.currency()) ?? null,
  );
  protected readonly bars = computed(() =>
    bars(this.timeline()?.points ?? [], CHART_WIDTH, CHART_HEIGHT),
  );

  constructor() {
    this.accountsApi.list(0, 100).subscribe({
      next: (page) =>
        this.accounts.set(page.items.filter((a) => a.userId === this.auth.user()?.id)),
      error: () => undefined,
    });

    // Summary first: it tells which currencies there are; the rest follows the chosen one.
    effect(() => {
      const range = this.range();
      const accountId = this.accountId() || undefined;
      this.loading.set(true);
      this.error.set('');
      this.api.summary(range, accountId).subscribe({
        next: (summary) => {
          this.summary.set(summary);
          const codes = summary.currencies.map((c) => c.currency);
          if (!codes.includes(this.currency())) {
            this.currency.set(codes[0] ?? '');
          }
          this.loading.set(false);
        },
        error: (error) => this.fail(error),
      });
      if (this.auth.isAdmin()) {
        this.api
          .overview(range)
          .subscribe({ next: (o) => this.overview.set(o), error: () => this.overview.set(null) });
      }
    });

    effect(() => {
      const currency = this.currency();
      const range = this.range();
      const accountId = this.accountId() || undefined;
      if (!currency) {
        this.timeline.set(null);
        this.outgoing.set(null);
        this.incoming.set(null);
        return;
      }
      of(null)
        .pipe(
          switchMap(() =>
            forkJoin({
              timeline: this.api.timeline(currency, intervalFor(this.days()), range, accountId),
              outgoing: this.api.counterparties(currency, 'OUTGOING', range),
              incoming: this.api.counterparties(currency, 'INCOMING', range),
            }),
          ),
        )
        .subscribe({
          next: ({ timeline, outgoing, incoming }) => {
            this.timeline.set(timeline);
            this.outgoing.set(outgoing);
            this.incoming.set(incoming);
          },
          error: (error) => this.fail(error),
        });
    });
  }

  protected label(accountId: string): string {
    return accountId.startsWith('EXTERNAL-') ? 'Outside the bank' : accountId;
  }

  private fail(error: unknown): void {
    this.loading.set(false);
    const status = error instanceof HttpErrorResponse ? error.status : 0;
    this.error.set(
      status === 503
        ? 'Analytics are temporarily unavailable. Your accounts and transfers are not affected.'
        : 'Analytics could not be loaded.',
    );
  }
}
