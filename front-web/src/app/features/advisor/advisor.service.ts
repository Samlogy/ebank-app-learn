import { HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { EMPTY, expand, forkJoin, reduce, Subscription } from 'rxjs';

import { RealtimeService } from '../../core/realtime/realtime.service';
import {
  AdvisorChatApi,
  AdvisorConversation,
  ChatEvent,
  ChatMessage,
  lastSeq,
  MESSAGES_PAGE,
  mergeMessages,
} from '../chatbot/chat-api';

/**
 * The advisor console. While {@link enter}ed, the advisor is subscribed to /user/queue/chat,
 * which is what the backend counts as "online": new conversations are then assigned to the least
 * busy online advisor on their own, and customers are told someone is there.
 */
@Injectable({ providedIn: 'root' })
export class AdvisorService {
  private readonly api = inject(AdvisorChatApi);
  private readonly realtime = inject(RealtimeService);
  private live?: Subscription;
  private readSent = 0;

  readonly queue = signal<AdvisorConversation[]>([]);
  readonly mine = signal<AdvisorConversation[]>([]);
  readonly selected = signal<AdvisorConversation | null>(null);
  readonly messages = signal<ChatMessage[]>([]);
  readonly readByCustomer = signal(0);
  readonly loaded = signal(false);
  readonly error = signal('');

  readonly canWrite = computed(
    () => this.selected()?.state === 'WITH_ADVISOR' && this.isMine(this.selected()!.id),
  );

  enter(): void {
    if (!this.live) {
      this.live = this.realtime
        .watch<ChatEvent>('/user/queue/chat')
        .subscribe((event) => this.onEvent(event));
      this.live.add(this.realtime.reconnected$.subscribe(() => this.refresh()));
    }
    this.refresh();
  }

  leave(): void {
    this.live?.unsubscribe();
    this.live = undefined;
  }

  refresh(): void {
    forkJoin({ queue: this.api.queue(), mine: this.api.mine() }).subscribe({
      next: ({ queue, mine }) => {
        this.queue.set(queue);
        this.mine.set(mine);
        this.loaded.set(true);
        const selected = this.selected();
        if (selected) {
          const fresh = [...queue, ...mine].find((c) => c.id === selected.id);
          if (fresh) {
            this.selected.set(fresh);
          }
          this.catchUp();
        }
      },
      error: (error) => this.fail(error, 'The queue could not be loaded.'),
    });
  }

  select(conversation: AdvisorConversation): void {
    if (this.selected()?.id === conversation.id) {
      return;
    }
    this.selected.set(conversation);
    this.messages.set([]);
    this.readByCustomer.set(0);
    this.readSent = 0;
    this.error.set('');
    this.catchUp();
  }

  claim(): void {
    const conversation = this.selected();
    if (!conversation) {
      return;
    }
    this.api.claim(conversation.id).subscribe({
      next: (claimed) => {
        this.selected.set(claimed);
        this.refresh();
      },
      error: (error) =>
        this.fail(
          error,
          error instanceof HttpErrorResponse && error.status === 409
            ? 'Another advisor took this conversation first.'
            : 'The conversation could not be taken.',
        ),
    });
  }

  send(text: string): void {
    const conversation = this.selected();
    if (!conversation || !this.canWrite()) {
      return;
    }
    this.api.send(conversation.id, crypto.randomUUID(), text).subscribe({
      next: (message) => this.messages.update((list) => mergeMessages(list, [message])),
      error: (error) => this.fail(error, 'Your message could not be sent.'),
    });
  }

  resolve(): void {
    const conversation = this.selected();
    if (!conversation) {
      return;
    }
    this.api.resolve(conversation.id).subscribe({
      next: (resolved) => {
        this.selected.set(resolved);
        this.refresh();
      },
      error: (error) => this.fail(error, 'The conversation could not be closed.'),
    });
  }

  markRead(): void {
    const conversation = this.selected();
    const seq = lastSeq(this.messages());
    if (!conversation || !this.isMine(conversation.id) || seq <= this.readSent) {
      return;
    }
    this.readSent = seq;
    this.api.read(conversation.id, seq).subscribe({ error: () => (this.readSent = 0) });
  }

  private isMine(id: string): boolean {
    return this.mine().some((c) => c.id === id);
  }

  private catchUp(): void {
    const conversation = this.selected();
    if (!conversation) {
      return;
    }
    const page = (after: number) => this.api.messages(conversation.id, after);
    page(lastSeq(this.messages()))
      .pipe(
        expand((batch) => (batch.length === MESSAGES_PAGE ? page(lastSeq(batch)) : EMPTY)),
        reduce((all, batch) => [...all, ...batch], [] as ChatMessage[]),
      )
      .subscribe({
        next: (messages) => {
          if (this.selected()?.id === conversation.id) {
            this.messages.update((list) => mergeMessages(list, messages));
          }
        },
        error: (error) => this.fail(error, 'Messages could not be loaded.'),
      });
  }

  private onEvent(event: ChatEvent): void {
    if (event.type === 'QUEUE' || event.type === 'STATE') {
      // Assignments and state changes reshape both lists: reload them.
      this.refresh();
      return;
    }
    if (event.conversationId !== this.selected()?.id) {
      return;
    }
    if ((event.type === 'MESSAGE' || event.type === 'DELETED') && event.message) {
      const message = event.message;
      if (event.type === 'MESSAGE' && message.seq > lastSeq(this.messages()) + 1) {
        this.catchUp();
      } else {
        this.messages.update((list) => mergeMessages(list, [message]));
      }
    } else if (event.type === 'READ' && event.readSeq !== null) {
      this.readByCustomer.update((seq) => Math.max(seq, event.readSeq!));
    }
  }

  private fail(error: unknown, message: string): void {
    const status = error instanceof HttpErrorResponse ? error.status : 0;
    this.error.set(status === 429 ? 'Too many messages. Please wait a moment.' : message);
  }
}
