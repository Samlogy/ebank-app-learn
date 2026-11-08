import { inject, Injectable, signal } from '@angular/core';

import { AttachmentKind, ChatAttachment, ChatTransport } from './chat-transport';

export interface ChatMessage {
  id: string;
  from: 'me' | 'bot';
  text: string;
  attachments: ChatAttachment[];
  sentAt: string;
}

export const MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024;

/** Lives at the root so the conversation survives moving to another page and back. */
@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly transport = inject(ChatTransport);

  readonly simulated = this.transport.simulated;
  readonly messages = signal<ChatMessage[]>([]);
  readonly botTyping = signal(false);

  constructor() {
    this.transport.events$.subscribe((event) => {
      if (event.type === 'typing') {
        this.botTyping.set(event.active);
      } else {
        this.messages.update((list) => [...list, { ...event.message, from: 'bot' }]);
      }
    });
  }

  send(text: string, attachments: ChatAttachment[]): void {
    const id = crypto.randomUUID();
    this.messages.update((list) => [
      ...list,
      { id, from: 'me', text, attachments, sentAt: new Date().toISOString() },
    ]);
    this.transport.send({ id, text, attachments });
  }
}

export function toAttachment(file: File): ChatAttachment {
  return {
    id: crypto.randomUUID(),
    name: file.name,
    size: file.size,
    mimeType: file.type,
    kind: kindOf(file.type),
    url: URL.createObjectURL(file),
  };
}

export function kindOf(mimeType: string): AttachmentKind {
  if (mimeType.startsWith('image/')) {
    return 'image';
  }
  if (mimeType.startsWith('video/')) {
    return 'video';
  }
  return mimeType.startsWith('audio/') ? 'audio' : 'file';
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  return bytes < 1024 * 1024
    ? `${Math.round(bytes / 1024)} KB`
    : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
