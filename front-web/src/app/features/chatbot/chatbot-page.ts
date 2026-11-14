import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  OnDestroy,
  OnInit,
} from '@angular/core';

import { RealtimeService } from '../../core/realtime/realtime.service';
import { Icon } from '../../shared/components/icon';
import { ConversationState } from './chat-api';
import { ChatComposer } from './chat-composer';
import { ChatTranscript } from './chat-transcript';
import { ChatService } from './chat.service';

const STATUS: Record<ConversationState, string> = {
  BOT: 'Assistant · ask about your accounts, balance or recent transfers',
  WAITING_ADVISOR: 'Waiting for an advisor · you can keep writing',
  WITH_ADVISOR: 'With an advisor',
  RESOLVED: 'Conversation closed',
};

@Component({
  selector: 'app-chatbot-page',
  imports: [Icon, ChatTranscript, ChatComposer],
  templateUrl: './chatbot-page.html',
  styleUrl: './chatbot-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatbotPage implements OnInit, OnDestroy {
  protected readonly chat = inject(ChatService);
  protected readonly realtime = inject(RealtimeService);

  protected readonly status = computed(() => {
    const state = this.chat.state();
    return state ? STATUS[state] : 'Connecting…';
  });
  protected readonly title = computed(() =>
    this.chat.state() === 'WITH_ADVISOR' ? 'EBANK Advisor' : 'EBANK Assistant',
  );

  constructor() {
    // The page is on screen: whatever arrives has been seen.
    effect(() => {
      this.chat.messages();
      if (!document.hidden) {
        this.chat.markRead();
      }
    });
  }

  ngOnInit(): void {
    this.chat.enter();
  }

  ngOnDestroy(): void {
    this.chat.leave();
  }
}
