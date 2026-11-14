import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../core/config/app-config';

export type Interval = 'DAY' | 'WEEK' | 'MONTH';
export type Direction = 'INCOMING' | 'OUTGOING';

export interface Amount {
  total: number;
  count: number;
}

export interface CurrencySummary {
  currency: string;
  incoming: Amount;
  outgoing: Amount;
  /** Between the user's own accounts. Does not change the net. */
  internal: Amount;
  net: number;
  /** Outgoing transfers that were refused, such as for insufficient funds. */
  cancelledCount: number;
}

export interface Summary {
  from: string;
  to: string;
  accountId: string | null;
  currencies: CurrencySummary[];
}

export interface TimelinePoint {
  start: string;
  incoming: number;
  outgoing: number;
  net: number;
}

export interface Timeline {
  currency: string;
  interval: Interval;
  from: string;
  to: string;
  points: TimelinePoint[];
}

export interface CounterpartyTotal {
  /** EXTERNAL-<currency> for money from outside the bank. */
  accountId: string;
  total: number;
  count: number;
}

export interface Counterparties {
  currency: string;
  direction: Direction;
  items: CounterpartyTotal[];
}

export interface CurrencyOverview {
  currency: string;
  transfers: Amount;
  deposits: Amount;
  cancelled: Amount;
  activeUsers: number;
}

export interface Overview {
  from: string;
  to: string;
  currencies: CurrencyOverview[];
}

/** Inclusive UTC days, as the API expects them. */
export interface DayRange {
  from: string;
  to: string;
}

@Injectable({ providedIn: 'root' })
export class AnalyticsApi {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).apiUrl}/analytics`;

  summary(range: DayRange, accountId?: string): Observable<Summary> {
    return this.http.get<Summary>(`${this.url}/summary`, { params: params(range, { accountId }) });
  }

  timeline(
    currency: string,
    interval: Interval,
    range: DayRange,
    accountId?: string,
  ): Observable<Timeline> {
    return this.http.get<Timeline>(`${this.url}/timeline`, {
      params: params(range, { currency, interval, accountId }),
    });
  }

  counterparties(
    currency: string,
    direction: Direction,
    range: DayRange,
    limit = 5,
  ): Observable<Counterparties> {
    return this.http.get<Counterparties>(`${this.url}/counterparties`, {
      params: params(range, { currency, direction, limit: String(limit) }),
    });
  }

  /** Admin only. */
  overview(range: DayRange): Observable<Overview> {
    return this.http.get<Overview>(`${this.url}/overview`, { params: params(range, {}) });
  }
}

function params(range: DayRange, extra: Record<string, string | undefined>): HttpParams {
  let result = new HttpParams().set('from', range.from).set('to', range.to);
  for (const [key, value] of Object.entries(extra)) {
    if (value) {
      result = result.set(key, value);
    }
  }
  return result;
}

/** The last {@link days} UTC days, today included. */
export function lastDays(days: number, now = new Date()): DayRange {
  const to = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const from = new Date(to);
  from.setUTCDate(from.getUTCDate() - (days - 1));
  return { from: isoDay(from), to: isoDay(to) };
}

/** Enough bars to read, never hundreds: days up to a month, weeks up to a quarter, then months. */
export function intervalFor(days: number): Interval {
  return days <= 31 ? 'DAY' : days <= 120 ? 'WEEK' : 'MONTH';
}

function isoDay(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export interface Bar {
  start: string;
  x: number;
  width: number;
  /** Incoming, drawn up from the middle line. */
  inY: number;
  inHeight: number;
  /** Outgoing, drawn down from the middle line. */
  outY: number;
  outHeight: number;
  incoming: number;
  outgoing: number;
}

/**
 * Bar geometry for a chart {@link width} by {@link height}: money in above the middle line, money
 * out below it, both on the same scale so they compare.
 */
export function bars(points: TimelinePoint[], width: number, height: number, gap = 2): Bar[] {
  if (points.length === 0) {
    return [];
  }
  const max = Math.max(1, ...points.flatMap((p) => [p.incoming, p.outgoing]));
  const middle = height / 2;
  const slot = width / points.length;
  const barWidth = Math.max(1, slot - gap);
  return points.map((p, i) => {
    const inHeight = (p.incoming / max) * middle;
    const outHeight = (p.outgoing / max) * middle;
    return {
      start: p.start,
      x: i * slot + gap / 2,
      width: barWidth,
      inY: middle - inHeight,
      inHeight,
      outY: middle,
      outHeight,
      incoming: p.incoming,
      outgoing: p.outgoing,
    };
  });
}
