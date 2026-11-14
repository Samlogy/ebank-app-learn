import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';

import { AuthService } from '../../core/auth/auth.service';
import { RealtimeService } from '../../core/realtime/realtime.service';
import { NotificationDto, NotificationsApi } from './notifications-api';
import { NotificationsService, toNotification } from './notifications.service';

function dto(id: string, overrides: Partial<NotificationDto> = {}): NotificationDto {
  return {
    id,
    type: 'TRANSFER_RECEIVED',
    transactionId: `TXN-${id}`,
    accountId: 'EB01',
    counterparty: 'EB02',
    amount: 10,
    currency: 'EUR',
    createdAt: '2026-11-14T10:00:00Z',
    read: false,
    ...overrides,
  };
}

describe('toNotification', () => {
  it.each([
    ['TRANSFER_RECEIVED', 'received'],
    ['TRANSFER_SENT', 'sent'],
    ['TRANSFER_INTERNAL', 'internal'],
    ['TRANSFER_CANCELLED', 'cancelled'],
    ['ADVISOR_MESSAGE', 'advisor'],
  ] as const)('maps %s to %s', (type, kind) => {
    expect(toNotification(dto('1', { type })).kind).toBe(kind);
  });

  it('keeps the source id to link the transfer or conversation', () => {
    expect(toNotification(dto('1')).sourceId).toBe('TXN-1');
  });
});

describe('NotificationsService', () => {
  let pushes: Subject<NotificationDto>;
  let reconnects: Subject<void>;
  const api = {
    list: vi.fn(),
    unreadCount: vi.fn(),
    markRead: vi.fn(),
    markAllRead: vi.fn(),
  };
  let service: NotificationsService;

  beforeEach(() => {
    vi.resetAllMocks();
    pushes = new Subject<NotificationDto>();
    reconnects = new Subject<void>();
    api.list.mockReturnValue(
      of({
        items: [dto('a'), dto('b', { read: true })],
        page: 0,
        size: 20,
        totalElements: 2,
        totalPages: 1,
      }),
    );
    api.unreadCount.mockReturnValue(of({ count: 1 }));
    api.markRead.mockReturnValue(of(dto('a', { read: true })));
    api.markAllRead.mockReturnValue(of({ updated: 1 }));
    TestBed.configureTestingModule({
      providers: [
        { provide: NotificationsApi, useValue: api },
        { provide: AuthService, useValue: { user: signal({ id: 'me' }) } },
        {
          provide: RealtimeService,
          useValue: { watch: () => pushes.asObservable(), reconnected$: reconnects.asObservable() },
        },
      ],
    });
    service = TestBed.inject(NotificationsService);
    service.start();
    service.refresh();
  });

  it('loads the list and the unread count from the API', () => {
    expect(service.items().map((n) => n.id)).toEqual(['a', 'b']);
    expect(service.unreadCount()).toBe(1);
  });

  it('puts a pushed notification first and counts it as unread', () => {
    pushes.next(dto('c'));
    expect(service.items()[0].id).toBe('c');
    expect(service.unreadCount()).toBe(2);
  });

  it('ignores a push it already has', () => {
    pushes.next(dto('a'));
    expect(service.items()).toHaveLength(2);
    expect(service.unreadCount()).toBe(1);
  });

  it('marks one as read on the server and in the badge', () => {
    service.markRead('a');
    expect(api.markRead).toHaveBeenCalledWith('a');
    expect(service.items()[0].read).toBe(true);
    expect(service.unreadCount()).toBe(0);
  });

  it('does not call the server for one already read', () => {
    service.markRead('b');
    expect(api.markRead).not.toHaveBeenCalled();
  });

  it('marks everything as read', () => {
    service.markAllRead();
    expect(api.markAllRead).toHaveBeenCalledOnce();
    expect(service.items().every((n) => n.read)).toBe(true);
    expect(service.unreadCount()).toBe(0);
  });

  it('reloads after a reconnect, since pushes may have been missed', () => {
    api.list.mockClear();
    reconnects.next();
    expect(api.list).toHaveBeenCalledOnce();
  });
});
