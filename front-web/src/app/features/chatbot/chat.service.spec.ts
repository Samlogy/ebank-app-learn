import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';

import { ChatService, formatBytes, kindOf } from './chat.service';
import { ChatEvent, ChatTransport, OutgoingMessage } from './chat-transport';
import { demoReply } from './demo-chat-transport';

class FakeTransport extends ChatTransport {
  readonly simulated = false;
  readonly events = new Subject<ChatEvent>();
  readonly events$ = this.events.asObservable();
  readonly sent: OutgoingMessage[] = [];

  send(message: OutgoingMessage): void {
    this.sent.push(message);
  }
}

describe('ChatService', () => {
  let transport: FakeTransport;
  let chat: ChatService;

  beforeEach(() => {
    transport = new FakeTransport();
    TestBed.configureTestingModule({
      providers: [{ provide: ChatTransport, useValue: transport }],
    });
    chat = TestBed.inject(ChatService);
  });

  it('shows a sent message and hands it to the transport', () => {
    chat.send('hello', []);
    expect(chat.messages()).toHaveLength(1);
    expect(chat.messages()[0]).toMatchObject({ from: 'me', text: 'hello' });
    expect(transport.sent[0]).toMatchObject({ text: 'hello', id: chat.messages()[0].id });
  });

  it('adds messages as they arrive', () => {
    transport.events.next({
      type: 'message',
      message: { id: '1', text: 'hi', attachments: [], sentAt: '2026-11-08T10:00:00Z' },
    });
    expect(chat.messages()[0]).toMatchObject({ from: 'bot', text: 'hi' });
  });

  it('tracks the typing indicator', () => {
    transport.events.next({ type: 'typing', active: true });
    expect(chat.botTyping()).toBe(true);
    transport.events.next({ type: 'typing', active: false });
    expect(chat.botTyping()).toBe(false);
  });
});

describe('kindOf', () => {
  it.each([
    ['image/png', 'image'],
    ['video/webm', 'video'],
    ['audio/ogg', 'audio'],
    ['application/pdf', 'file'],
    ['', 'file'],
  ])('%s is %s', (mimeType, kind) => {
    expect(kindOf(mimeType)).toBe(kind);
  });
});

describe('formatBytes', () => {
  it('picks a readable unit', () => {
    expect(formatBytes(500)).toBe('500 B');
    expect(formatBytes(2048)).toBe('2 KB');
    expect(formatBytes(3 * 1024 * 1024)).toBe('3.0 MB');
  });
});

describe('demoReply', () => {
  it('echoes text', () => {
    expect(demoReply({ id: '1', text: 'hi', attachments: [] })).toContain('hi');
  });

  it('names the attachment types it returns', () => {
    const attachments = [{ kind: 'audio' }, { kind: 'image' }] as OutgoingMessage['attachments'];
    const reply = demoReply({ id: '1', text: '', attachments });
    expect(reply).toContain('voice message');
    expect(reply).toContain('picture');
  });
});
