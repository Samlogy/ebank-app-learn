import { Observable } from 'rxjs';

export type AttachmentKind = 'image' | 'video' | 'audio' | 'file';

export interface ChatAttachment {
  id: string;
  name: string;
  size: number;
  mimeType: string;
  kind: AttachmentKind;
  /** Where the browser loads the content from: a blob: URL here, an API URL from a real backend. */
  url: string;
}

export interface OutgoingMessage {
  id: string;
  text: string;
  attachments: ChatAttachment[];
}

export interface IncomingMessage {
  id: string;
  text: string;
  attachments: ChatAttachment[];
  sentAt: string;
}

export type ChatEvent =
  { type: 'typing'; active: boolean } | { type: 'message'; message: IncomingMessage };

/**
 * The page depends only on this contract. A WebSocket implementation can replace the demo one
 * in app.config.ts without touching the UI.
 */
export abstract class ChatTransport {
  /** True while replies are simulated, so the page can tell the user. */
  abstract readonly simulated: boolean;
  abstract readonly events$: Observable<ChatEvent>;
  abstract send(message: OutgoingMessage): void;
}
