import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { Page } from '../../shared/models/page';

export type AccountType = 'SAVING' | 'CURRENT' | 'CRYPTO';
export type AccountStatus = 'CREATED' | 'ACTIVE' | 'DISABLED' | 'CLOSED';
export type AccountAction = 'activate' | 'disable' | 'close';

export interface Account {
  accountId: string;
  type: AccountType;
  status: AccountStatus;
  currency: string;
  userId: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface AccountHistoryEntry {
  action: 'CREATED' | 'TYPE_CHANGED' | 'ACTIVATED' | 'DISABLED' | 'CLOSED';
  statusBefore: AccountStatus | null;
  statusAfter: AccountStatus;
  typeBefore: AccountType | null;
  typeAfter: AccountType;
  changedBy: string;
  changedAt: string;
}

export interface Balance {
  accountId: string;
  amount: number;
  currency: string;
}

@Injectable({ providedIn: 'root' })
export class AccountsApi {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/accounts`;

  list(page = 0, size = 20): Observable<Page<Account>> {
    return this.http.get<Page<Account>>(this.url, { params: { page, size } });
  }

  create(type: AccountType, currency: string): Observable<Account> {
    return this.http.post<Account>(this.url, { type, currency });
  }

  change(accountId: string, action: AccountAction): Observable<Account> {
    return this.http.patch<Account>(`${this.url}/${encodeURIComponent(accountId)}/${action}`, null);
  }

  balance(accountId: string): Observable<Balance> {
    return this.http.get<Balance>(`${this.url}/${encodeURIComponent(accountId)}/balance`);
  }

  history(accountId: string): Observable<Page<AccountHistoryEntry>> {
    return this.http.get<Page<AccountHistoryEntry>>(
      `${this.url}/${encodeURIComponent(accountId)}/history`,
    );
  }
}
