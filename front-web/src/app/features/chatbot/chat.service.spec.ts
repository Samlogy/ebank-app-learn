import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, Subject, throwError } from 'rxjs';

import { RealtimeService } from '../../core/realtime/realtime.service';
import {
  ChatEvent,
  ChatMessage,
  CustomerChatApi,
  CustomerConversation,
  mergeMessages,
} from './chat-api';
import { ChatService } from './chat.service';

const conversation: CustomerConversation = {
  id: 'c1',
  state: 'BOT',
  handoffReason: null,
  lastSeq: 0,
  createdAt: '2026-11-14T10:00:00Z',
  updatedAt: '2026-11-14T10:00:00Z',
};

function message(seq: number, senderType: ChatMessage['senderType'] = 'CUSTOMER'): ChatMessage {
  return {
    id: `m${seq}`,
    seq,
    senderType,
    type: 'TEXT',
    content: `text ${seq}`,
    clientMessageId: null,
    createdAt: '2026-11-14T10:00:00Z',
    deleted: false,
  };
}

function event(partial: Partial<ChatEvent>): ChatEvent {
  return {
    type: 'MESSAGE',
    conversationId: 'c1',
    message: null,
    state: null,
    readSeq: null,
    typing: null,
    ...partial,
  };
}

describe('mergeMessages', () => {
  it('keeps one copy of each message, in seq order', () => {
    const merged = mergeMessages([message(1), message(3)], [message(2), message(3)]);
    expect(merged.map((m) => m.seq)).toEqual([1, 2, 3]);
  });

  it('replaces a message by a newer copy, such as a deleted one', () => {
    const merged = mergeMessages([message(1)], [{ ...message(1), deleted: true }]);
    expect(merged[0].deleted).toBe(true);
  });
});

describe('ChatService', () => {
  let events: Subject<ChatEvent>;
  let reconnects: Subject<void>;
  const api = {
    open: vi.fn(),
    messages: vi.fn(),
    send: vi.fn(),
    delete: vi.fn(),
    handoff: vi.fn(),
    backToBot: vi.fn(),
    resolve: vi.fn(),
    read: vi.fn(),
  };
  let chat: ChatService;

  beforeEach(() => {
    vi.resetAllMocks();
    events = new Subject<ChatEvent>();
    reconnects = new Subject<void>();
    api.open.mockReturnValue(of(conversation));
    api.messages.mockReturnValue(of([message(1), message(2, 'ASSISTANT')]));
    api.read.mockReturnValue(of(undefined));
    TestBed.configureTestingModule({
      providers: [
        { provide: CustomerChatApi, useValue: api },
        {
          provide: RealtimeService,
          useValue: { watch: () => events.asObservable(), reconnected$: reconnects.asObservable() },
        },
      ],
    });
    chat = TestBed.inject(ChatService);
    chat.enter();
  });

  it('opens the conversation and loads its messages', () => {
    expect(chat.conversation()?.id).toBe('c1');
    expect(chat.messages().map((m) => m.seq)).toEqual([1, 2]);
    expect(api.messages).toHaveBeenCalledWith('c1', 0);
  });

  it('loads the next page when a page comes back full', () => {
    const full = Array.from({ length: 100 }, (_, i) => message(i + 1));
    api.messages.mockReset();
    api.messages.mockReturnValueOnce(of(full)).mockReturnValueOnce(of([message(101)]));
    chat.leave();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: CustomerChatApi, useValue: api },
        {
          provide: RealtimeService,
          useValue: { watch: () => events.asObservable(), reconnected$: reconnects.asObservable() },
        },
      ],
    });
    const fresh = TestBed.inject(ChatService);
    fresh.enter();
    expect(api.messages).toHaveBeenLastCalledWith('c1', 100);
    expect(fresh.messages()).toHaveLength(101);
  });

  it('sends with a client message id and shows the stored message', () => {
    api.send.mockReturnValue(of(message(3)));
    chat.send('  hello  ');
    expect(api.send).toHaveBeenCalledWith('c1', expect.any(String), 'hello');
    expect(chat.lastSeq()).toBe(3);
  });

  it('does not send blank text', () => {
    chat.send('   ');
    expect(api.send).not.toHaveBeenCalled();
  });

  it('adds a pushed message once, even when the REST answer brings it too', () => {
    events.next(event({ message: message(3, 'ADVISOR') }));
    api.send.mockReturnValue(of(message(3, 'ADVISOR')));
    chat.send('x');
    expect(chat.messages().filter((m) => m.seq === 3)).toHaveLength(1);
  });

  it('reloads when a push skips a sequence number', () => {
    api.messages.mockClear();
    api.messages.mockReturnValue(of([message(3), message(4)]));
    events.next(event({ message: message(4) }));
    expect(api.messages).toHaveBeenCalledWith('c1', 2);
    expect(chat.messages().map((m) => m.seq)).toEqual([1, 2, 3, 4]);
  });

  it('ignores events of another conversation', () => {
    events.next(event({ conversationId: 'other', message: message(3) }));
    expect(chat.messages()).toHaveLength(2);
  });

  it('follows state changes, typing and read receipts', () => {
    events.next(event({ type: 'STATE', state: 'WITH_ADVISOR' }));
    events.next(event({ type: 'TYPING', typing: true }));
    events.next(event({ type: 'READ', readSeq: 2 }));
    expect(chat.state()).toBe('WITH_ADVISOR');
    expect(chat.assistantTyping()).toBe(true);
    expect(chat.readByOther()).toBe(2);
  });

  it('sends a read receipt once per new message', () => {
    chat.markRead();
    chat.markRead();
    expect(api.read).toHaveBeenCalledOnce();
    expect(api.read).toHaveBeenCalledWith('c1', 2);
  });

  it('asks for an advisor', () => {
    api.handoff.mockReturnValue(of({ ...conversation, state: 'WAITING_ADVISOR' }));
    chat.handoff();
    expect(chat.state()).toBe('WAITING_ADVISOR');
  });

  it('explains the rate limit', () => {
    api.send.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 429 })));
    chat.send('hi');
    expect(chat.error()).toContain('too fast');
  });

  it('catches up after a reconnect', () => {
    api.messages.mockClear();
    api.messages.mockReturnValue(of([]));
    reconnects.next();
    expect(api.messages).toHaveBeenCalledWith('c1', 2);
  });
});
