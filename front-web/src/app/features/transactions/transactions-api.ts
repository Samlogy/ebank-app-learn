import { HttpClient, HttpHeaders } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { Page } from '../../shared/models/page';

export type TransferStatus = 'PENDING' | 'DONE' | 'CANCELLED';
export type TransferDirection = 'in' | 'out' | 'internal' | 'other';

export interface Transfer {
  transactionId: string;
  senderAccountId: string;
  receiverAccountId: string;
  amount: number;
  currency: string;
  reference: string | null;
  status: TransferStatus;
  failureReason: string | null;
  createdAt: string;
}

export interface TransferRequest {
  senderAccountId: string;
  receiverAccountId: string;
  /** A string, so the amount reaches the backend's BigDecimal without passing through a double. */
  amount: string;
  currency: string;
  reference?: string;
}

export interface TransferSearch {
  transactionId?: string;
  accountId?: string;
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class TransactionsApi {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/transactions`;

  search(search: TransferSearch = {}): Observable<Page<Transfer>> {
    const params = Object.fromEntries(
      Object.entries(search).filter(([, value]) => value !== undefined && value !== ''),
    );
    return this.http.get<Page<Transfer>>(this.url, { params });
  }

  transfer(request: TransferRequest, idempotencyKey: string): Observable<Transfer> {
    const headers = new HttpHeaders({ 'Idempotency-Key': idempotencyKey });
    return this.http.post<Transfer>(this.url, request, { headers });
  }
}

export function directionOf(transfer: Transfer, ownAccountIds: Set<string>): TransferDirection {
  const sent = ownAccountIds.has(transfer.senderAccountId);
  const received = ownAccountIds.has(transfer.receiverAccountId);
  if (sent && received) {
    return 'internal';
  }
  return sent ? 'out' : received ? 'in' : 'other';
}
