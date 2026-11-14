import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../core/config/app-config';
import { Page } from '../../shared/models/page';

export type NotificationType =
  | 'TRANSFER_RECEIVED'
  | 'TRANSFER_SENT'
  | 'TRANSFER_INTERNAL'
  | 'TRANSFER_CANCELLED'
  | 'ADVISOR_MESSAGE';

/** As returned by the API and pushed on /user/queue/notifications. */
export interface NotificationDto {
  id: string;
  type: NotificationType;
  /** The transfer, or the conversation for ADVISOR_MESSAGE. */
  transactionId: string | null;
  accountId: string | null;
  counterparty: string | null;
  amount: number | null;
  currency: string | null;
  createdAt: string;
  read: boolean;
}

export interface Preferences {
  emailEnabled: boolean;
  smsEnabled: boolean;
}

@Injectable({ providedIn: 'root' })
export class NotificationsApi {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).apiUrl}/notifications`;

  list(page = 0, size = 20, unreadOnly = false): Observable<Page<NotificationDto>> {
    return this.http.get<Page<NotificationDto>>(this.url, { params: { page, size, unreadOnly } });
  }

  unreadCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.url}/unread-count`);
  }

  markRead(id: string): Observable<NotificationDto> {
    return this.http.patch<NotificationDto>(`${this.url}/${encodeURIComponent(id)}/read`, null);
  }

  markAllRead(): Observable<{ updated: number }> {
    return this.http.post<{ updated: number }>(`${this.url}/read-all`, null);
  }

  preferences(): Observable<Preferences> {
    return this.http.get<Preferences>(`${this.url}/preferences`);
  }

  savePreferences(preferences: Preferences): Observable<Preferences> {
    return this.http.put<Preferences>(`${this.url}/preferences`, preferences);
  }
}
