import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  signal,
  viewChild,
} from '@angular/core';

import { Icon } from '../../shared/components/icon';
import { LocalDatePipe } from '../../shared/pipes/local-date.pipe';
import { formatBytes, MAX_ATTACHMENT_BYTES, ChatService, toAttachment } from './chat.service';
import { ChatAttachment } from './chat-transport';
import { MediaRecorderService, RecordingMode } from './media-recorder.service';

@Component({
  selector: 'app-chatbot-page',
  imports: [Icon, LocalDatePipe],
  providers: [MediaRecorderService],
  templateUrl: './chatbot-page.html',
  styleUrl: './chatbot-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatbotPage {
  protected readonly chat = inject(ChatService);
  protected readonly recorder = inject(MediaRecorderService);
  private readonly log = viewChild.required<ElementRef<HTMLElement>>('log');

  protected readonly text = signal('');
  protected readonly pending = signal<ChatAttachment[]>([]);
  protected readonly error = signal('');
  protected readonly canSend = computed(() => !!this.text().trim() || this.pending().length > 0);
  protected readonly formatBytes = formatBytes;

  constructor() {
    afterRenderEffect(() => {
      this.chat.messages();
      this.chat.botTyping();
      this.scrollToEnd();
    });
  }

  protected scrollToEnd(): void {
    const log = this.log().nativeElement;
    log.scrollTop = log.scrollHeight;
  }

  protected onFilesPicked(input: HTMLInputElement): void {
    const files = Array.from(input.files ?? []);
    input.value = ''; // Otherwise picking the same file again does nothing.
    const tooBig = files.filter((file) => file.size > MAX_ATTACHMENT_BYTES);
    this.error.set(
      tooBig.length
        ? `${tooBig.map((file) => file.name).join(', ')}: files can be at most ${formatBytes(MAX_ATTACHMENT_BYTES)}.`
        : '',
    );
    this.addPending(files.filter((file) => file.size <= MAX_ATTACHMENT_BYTES));
  }

  protected removePending(attachment: ChatAttachment): void {
    URL.revokeObjectURL(attachment.url);
    this.pending.update((list) => list.filter((item) => item.id !== attachment.id));
  }

  protected async record(mode: RecordingMode): Promise<void> {
    this.error.set('');
    try {
      await this.recorder.start(mode, (file) => this.addPending([file]));
    } catch (error) {
      this.error.set((error as Error).message);
    }
  }

  protected onEnter(event: Event): void {
    const keyboard = event as KeyboardEvent;
    if (!keyboard.shiftKey && !keyboard.isComposing) {
      event.preventDefault();
      this.send();
    }
  }

  protected send(): void {
    if (!this.canSend()) {
      return;
    }
    this.chat.send(this.text().trim(), this.pending());
    this.text.set('');
    this.pending.set([]);
    this.error.set('');
  }

  protected clock(seconds: number): string {
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  }

  private addPending(files: File[]): void {
    this.pending.update((list) => [...list, ...files.map(toAttachment)]);
  }
}
