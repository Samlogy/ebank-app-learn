import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  input,
  output,
  viewChild,
} from '@angular/core';

import { Icon } from '../../shared/components/icon';
import { LocalDatePipe } from '../../shared/pipes/local-date.pipe';
import { ChatMessage, SenderType } from './chat-api';

const LABELS: Record<SenderType, string> = {
  CUSTOMER: 'Customer',
  ASSISTANT: 'Assistant',
  ADVISOR: 'Advisor',
  SYSTEM: '',
};

/** The message list, shared by the customer chat and the advisor console. */
@Component({
  selector: 'app-chat-transcript',
  imports: [Icon, LocalDatePipe],
  templateUrl: './chat-transcript.html',
  styleUrl: './chat-transcript.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatTranscript {
  readonly messages = input.required<ChatMessage[]>();
  /** Whose messages are shown on the right, as the reader's own. */
  readonly mine = input.required<SenderType>();
  /** Up to which seq the other side has read: shows "Seen" under the reader's last message. */
  readonly readByOther = input(0);
  readonly typing = input(false);
  /** Whether the reader may delete their own messages. */
  readonly canDelete = input(false);
  readonly emptyTitle = input('Start a conversation');
  readonly emptyText = input('');
  readonly deleteMessage = output<ChatMessage>();

  private readonly log = viewChild.required<ElementRef<HTMLElement>>('log');
  protected readonly labels = LABELS;

  /** The reader's last message, if the other side has read it. */
  protected readonly seenSeq = computed(() => {
    const own = this.messages().filter((m) => m.senderType === this.mine() && !m.deleted);
    const last = own[own.length - 1];
    return last && last.seq <= this.readByOther() ? last.seq : null;
  });

  constructor() {
    afterRenderEffect(() => {
      this.messages();
      this.typing();
      const log = this.log().nativeElement;
      log.scrollTop = log.scrollHeight;
    });
  }
}
