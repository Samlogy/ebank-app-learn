import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { Icon } from '../../shared/components/icon';
import { MAX_MESSAGE_LENGTH } from './chat-api';

/** Text input and send button. Enter sends, Shift+Enter adds a line. */
@Component({
  selector: 'app-chat-composer',
  imports: [Icon],
  template: `
    <div class="input-row">
      <textarea
        rows="1"
        [placeholder]="placeholder()"
        aria-label="Message"
        [attr.maxlength]="maxLength"
        [disabled]="disabled()"
        [value]="text()"
        (input)="text.set($any($event.target).value)"
        (keydown.enter)="onEnter($event)"
      ></textarea>
      <button
        class="btn primary send"
        type="button"
        aria-label="Send"
        [disabled]="!canSend()"
        (click)="submit()"
      >
        <app-icon name="send" />
      </button>
    </div>
  `,
  styles: `
    .input-row {
      display: flex;
      align-items: flex-end;
      gap: 0.25rem;
    }

    textarea {
      flex: 1;
      min-width: 0;
      min-height: 2.5rem;
      max-height: 8rem;
      padding: 0.5rem 0.75rem;
      border: 1px solid var(--border);
      border-radius: var(--radius-sm);
      background: var(--bg);
      font-size: 1rem;
      resize: none;
      field-sizing: content;
    }

    .send {
      flex: none;
      width: 2.5rem;
      padding: 0;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatComposer {
  readonly disabled = input(false);
  readonly placeholder = input('Message');
  readonly send = output<string>();

  protected readonly maxLength = MAX_MESSAGE_LENGTH;
  protected readonly text = signal('');
  protected readonly canSend = computed(() => !this.disabled() && this.text().trim().length > 0);

  protected onEnter(event: Event): void {
    const keyboard = event as KeyboardEvent;
    if (!keyboard.shiftKey && !keyboard.isComposing) {
      event.preventDefault();
      this.submit();
    }
  }

  protected submit(): void {
    if (this.canSend()) {
      this.send.emit(this.text().trim());
      this.text.set('');
    }
  }
}
