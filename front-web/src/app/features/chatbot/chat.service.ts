import { HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { EMPTY, expand, Observable, reduce, Subscription } from 'rxjs';

import { RealtimeService } from '../../core/realtime/realtime.service';
import {
  ChatEvent,
  ChatMessage,
  CustomerChatApi,
  CustomerConversation,
  lastSeq,
  MESSAGES_PAGE,
  mergeMessages,
} from './chat-api';

/**
 * The customer's conversation. Lives at the root, so it survives moving to another page and back.
 *
 * The page calls {@link enter} and {@link leave}: the live subscription to /user/queue/chat only
 * exists while the chat is on screen. The backend uses it to know the customer is "in the chat";
 * when they are not, an advisor's reply is sent as a notification instead.
 */
@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly api = inject(CustomerChatApi);
  private readonly realtime = inject(RealtimeService);
  private live?: Subscription;
  private readSent = 0;

  readonly conversation = signal<CustomerConversation | null>(null);
  readonly messages = signal<ChatMessage[]>([]);
  readonly assistantTyping = signal(false);
  /** Up to which message the other side (the advisor) has read. */
  readonly readByOther = signal(0);
  readonly loading = signal(false);
  readonly sending = signal(false);
  readonly error = signal('');

  readonly state = computed(() => this.conversation()?.state ?? null);
  readonly lastSeq = computed(() => lastSeq(this.messages()));

  enter(): void {
    if (!this.live) {
      this.live = this.realtime
        .watch<ChatEvent>('/user/queue/chat')
        .subscribe((event) => this.onEvent(event));
      this.live.add(this.realtime.reconnected$.subscribe(() => this.catchUp()));
    }
    if (this.conversation()) {
      this.catchUp();
    } else {
      this.open();
    }
  }

  leave(): void {
    this.live?.unsubscribe();
    this.live = undefined;
    this.assistantTyping.set(false);
  }

  /** The open conversation, or a new one after the previous was resolved. */
  open(): void {
    this.loading.set(true);
    this.api.open().subscribe({
      next: (conversation) => {
        if (conversation.id !== this.conversation()?.id) {
          this.messages.set([]);
          this.readByOther.set(0);
          this.readSent = 0;
        }
        this.conversation.set(conversation);
        this.catchUp();
      },
      error: (error) => this.fail(error, 'The chat is not available right now.'),
    });
  }

  send(text: string): void {
    const conversation = this.conversation();
    const content = text.trim();
    if (!conversation || !content || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.error.set('');
    // The same id on a retry makes the server store the message once.
    this.api.send(conversation.id, crypto.randomUUID(), content).subscribe({
      next: (message) => {
        this.messages.update((list) => mergeMessages(list, [message]));
        this.sending.set(false);
      },
      error: (error) => {
        this.sending.set(false);
        this.fail(error, 'Your message could not be sent.');
      },
    });
  }

  delete(message: ChatMessage): void {
    const conversation = this.conversation();
    if (!conversation) {
      return;
    }
    this.api.delete(conversation.id, message.id).subscribe({
      next: (deleted) => this.messages.update((list) => mergeMessages(list, [deleted])),
      error: (error) => this.fail(error, 'The message could not be deleted.'),
    });
  }

  handoff(): void {
    this.changeState((id) => this.api.handoff(id));
  }

  backToBot(): void {
    this.changeState((id) => this.api.backToBot(id));
  }

  resolve(): void {
    this.changeState((id) => this.api.resolve(id));
  }

  /** Tells the advisor what has been read. Sent once per new message, never backwards. */
  markRead(): void {
    const conversation = this.conversation();
    const seq = this.lastSeq();
    if (!conversation || seq <= this.readSent) {
      return;
    }
    this.readSent = seq;
    this.api.read(conversation.id, seq).subscribe({ error: () => (this.readSent = 0) });
  }

  private changeState(call: (id: string) => Observable<CustomerConversation>): void {
    const conversation = this.conversation();
    if (!conversation) {
      return;
    }
    this.error.set('');
    call(conversation.id).subscribe({
      next: (updated) => {
        this.conversation.set(updated);
        this.catchUp();
      },
      error: (error) => this.fail(error, 'That did not work. Please try again.'),
    });
  }

  /** Loads every message after the last one we have, a page at a time. */
  private catchUp(): void {
    const conversation = this.conversation();
    if (!conversation) {
      return;
    }
    const page = (after: number) => this.api.messages(conversation.id, after);
    page(this.lastSeq())
      .pipe(
        expand((batch) => (batch.length === MESSAGES_PAGE ? page(lastSeq(batch)) : EMPTY)),
        reduce((all, batch) => [...all, ...batch], [] as ChatMessage[]),
      )
      .subscribe({
        next: (messages) => {
          this.messages.update((list) => mergeMessages(list, messages));
          this.loading.set(false);
        },
        error: (error) => this.fail(error, 'Messages could not be loaded.'),
      });
  }

  private onEvent(event: ChatEvent): void {
    const conversation = this.conversation();
    if (!conversation || event.conversationId !== conversation.id) {
      return;
    }
    switch (event.type) {
      case 'MESSAGE':
      case 'DELETED':
        if (event.message) {
          const message = event.message;
          // A gap means a push was missed: reload instead of showing the transcript out of order.
          if (event.type === 'MESSAGE' && message.seq > this.lastSeq() + 1) {
            this.catchUp();
          } else {
            this.messages.update((list) => mergeMessages(list, [message]));
          }
          if (message.senderType === 'ASSISTANT') {
            this.assistantTyping.set(false);
          }
        }
        break;
      case 'STATE':
        if (event.state) {
          this.conversation.set({ ...conversation, state: event.state });
        }
        break;
      case 'READ':
        if (event.readSeq !== null) {
          this.readByOther.update((seq) => Math.max(seq, event.readSeq!));
        }
        break;
      case 'TYPING':
        this.assistantTyping.set(event.typing === true);
        break;
    }
  }

  private fail(error: unknown, message: string): void {
    this.loading.set(false);
    const status = error instanceof HttpErrorResponse ? error.status : 0;
    this.error.set(
      status === 429 ? 'You are sending messages too fast. Please wait a moment.' : message,
    );
  }
}
