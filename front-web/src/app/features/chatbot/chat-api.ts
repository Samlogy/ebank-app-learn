import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../core/config/app-config';
import { Page } from '../../shared/models/page';

export type ConversationState = 'BOT' | 'WAITING_ADVISOR' | 'WITH_ADVISOR' | 'RESOLVED';
export type HandoffReason = 'USER_REQUEST' | 'LLM_ESCALATION';
export type SenderType = 'CUSTOMER' | 'ASSISTANT' | 'ADVISOR' | 'SYSTEM';

export interface ChatMessage {
  id: string;
  /** Gap-free per conversation: the order of the transcript. */
  seq: number;
  senderType: SenderType;
  type: 'TEXT' | 'SYSTEM';
  content: string;
  clientMessageId: string | null;
  createdAt: string;
  deleted: boolean;
}

export interface CustomerConversation {
  id: string;
  state: ConversationState;
  handoffReason: HandoffReason | null;
  lastSeq: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdvisorConversation {
  id: string;
  customerId: string;
  state: ConversationState;
  handoffReason: HandoffReason | null;
  /** The assistant's summary of what the customer needs. */
  summary: string | null;
  lastSeq: number;
  handoffAt: string | null;
  assignedAt: string | null;
}

/** Pushed on /user/queue/chat. Only the fields that apply to the type are set. */
export interface ChatEvent {
  type: 'MESSAGE' | 'STATE' | 'READ' | 'DELETED' | 'TYPING' | 'QUEUE';
  conversationId: string;
  message: ChatMessage | null;
  state: ConversationState | null;
  readSeq: number | null;
  typing: boolean | null;
}

/** The API's page size limit for messages. */
export const MESSAGES_PAGE = 100;
export const MAX_MESSAGE_LENGTH = 2000;

/** Customer side: /chat/conversations. */
@Injectable({ providedIn: 'root' })
export class CustomerChatApi {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).apiUrl}/chat/conversations`;

  /** The open conversation, or a new one. */
  open(): Observable<CustomerConversation> {
    return this.http.post<CustomerConversation>(this.url, null);
  }

  list(page = 0, size = 20): Observable<Page<CustomerConversation>> {
    return this.http.get<Page<CustomerConversation>>(this.url, { params: { page, size } });
  }

  get(id: string): Observable<CustomerConversation> {
    return this.http.get<CustomerConversation>(`${this.url}/${id}`);
  }

  messages(id: string, afterSeq: number): Observable<ChatMessage[]> {
    return this.http.get<ChatMessage[]>(`${this.url}/${id}/messages`, {
      params: { afterSeq, limit: MESSAGES_PAGE },
    });
  }

  send(id: string, clientMessageId: string, text: string): Observable<ChatMessage> {
    return this.http.post<ChatMessage>(`${this.url}/${id}/messages`, { clientMessageId, text });
  }

  delete(id: string, messageId: string): Observable<ChatMessage> {
    return this.http.delete<ChatMessage>(`${this.url}/${id}/messages/${messageId}`);
  }

  handoff(id: string): Observable<CustomerConversation> {
    return this.http.post<CustomerConversation>(`${this.url}/${id}/handoff`, null);
  }

  backToBot(id: string): Observable<CustomerConversation> {
    return this.http.post<CustomerConversation>(`${this.url}/${id}/back-to-bot`, null);
  }

  resolve(id: string): Observable<CustomerConversation> {
    return this.http.post<CustomerConversation>(`${this.url}/${id}/resolve`, null);
  }

  read(id: string, seq: number): Observable<void> {
    return this.http.post<void>(`${this.url}/${id}/read`, { seq });
  }
}

/** Advisor side: /advisor. Needs the ADVISOR role. */
@Injectable({ providedIn: 'root' })
export class AdvisorChatApi {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(APP_CONFIG).apiUrl}/advisor`;

  /** Conversations waiting for an advisor, oldest first. */
  queue(): Observable<AdvisorConversation[]> {
    return this.http.get<AdvisorConversation[]>(`${this.url}/queue`);
  }

  /** Conversations assigned to the signed-in advisor. */
  mine(): Observable<AdvisorConversation[]> {
    return this.http.get<AdvisorConversation[]>(`${this.url}/conversations`);
  }

  get(id: string): Observable<AdvisorConversation> {
    return this.http.get<AdvisorConversation>(`${this.url}/conversations/${id}`);
  }

  messages(id: string, afterSeq: number): Observable<ChatMessage[]> {
    return this.http.get<ChatMessage[]>(`${this.url}/conversations/${id}/messages`, {
      params: { afterSeq, limit: MESSAGES_PAGE },
    });
  }

  claim(id: string): Observable<AdvisorConversation> {
    return this.http.post<AdvisorConversation>(`${this.url}/conversations/${id}/claim`, null);
  }

  send(id: string, clientMessageId: string, text: string): Observable<ChatMessage> {
    return this.http.post<ChatMessage>(`${this.url}/conversations/${id}/messages`, {
      clientMessageId,
      text,
    });
  }

  resolve(id: string): Observable<AdvisorConversation> {
    return this.http.post<AdvisorConversation>(`${this.url}/conversations/${id}/resolve`, null);
  }

  read(id: string, seq: number): Observable<void> {
    return this.http.post<void>(`${this.url}/conversations/${id}/read`, { seq });
  }
}

/** Adds messages to a transcript: no duplicates (a push and a REST answer can both bring one), in seq order. */
export function mergeMessages(current: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  if (incoming.length === 0) {
    return current;
  }
  const byId = new Map(current.map((m) => [m.id, m]));
  for (const message of incoming) {
    byId.set(message.id, message);
  }
  return [...byId.values()].sort((a, b) => a.seq - b.seq);
}

export function lastSeq(messages: ChatMessage[]): number {
  return messages.length ? messages[messages.length - 1].seq : 0;
}
