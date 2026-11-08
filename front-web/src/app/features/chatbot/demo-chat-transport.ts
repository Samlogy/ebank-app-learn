import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

import { AttachmentKind, ChatEvent, ChatTransport, OutgoingMessage } from './chat-transport';

const MIN_DELAY_MS = 500;
const DELAY_VARIATION_MS = 700;

const ATTACHMENT_WORDS: Record<AttachmentKind, string> = {
  image: 'picture',
  video: 'video',
  audio: 'voice message',
  file: 'file',
};

/**
 * Stands in for the chatbot backend, which is not built yet. It never gives banking answers:
 * it echoes what it gets, which exercises every message type in both directions.
 * Nothing leaves the browser.
 */
@Injectable()
export class DemoChatTransport extends ChatTransport {
  readonly simulated = true;
  private readonly events = new Subject<ChatEvent>();
  readonly events$ = this.events.asObservable();

  send(message: OutgoingMessage): void {
    this.events.next({ type: 'typing', active: true });
    setTimeout(
      () => {
        this.events.next({ type: 'typing', active: false });
        this.events.next({
          type: 'message',
          message: {
            id: crypto.randomUUID(),
            text: demoReply(message),
            attachments: message.attachments,
            sentAt: new Date().toISOString(),
          },
        });
      },
      MIN_DELAY_MS + Math.random() * DELAY_VARIATION_MS,
    );
  }
}

export function demoReply(message: OutgoingMessage): string {
  const words = [...new Set(message.attachments.map((a) => ATTACHMENT_WORDS[a.kind]))];
  const parts: string[] = [];
  if (message.text) {
    parts.push(`You said: “${message.text}”.`);
  }
  if (words.length) {
    parts.push(`Here is your ${words.join(', ')} back.`);
  }
  return parts.join(' ');
}
