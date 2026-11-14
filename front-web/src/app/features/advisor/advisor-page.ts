import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  OnDestroy,
  OnInit,
} from '@angular/core';

import { RealtimeService } from '../../core/realtime/realtime.service';
import { LocalDatePipe } from '../../shared/pipes/local-date.pipe';
import { AdvisorConversation, HandoffReason } from '../chatbot/chat-api';
import { ChatComposer } from '../chatbot/chat-composer';
import { ChatTranscript } from '../chatbot/chat-transcript';
import { AdvisorService } from './advisor.service';

const REASONS: Record<HandoffReason, string> = {
  USER_REQUEST: 'Asked for an advisor',
  LLM_ESCALATION: 'Sent by the assistant',
};

@Component({
  selector: 'app-advisor-page',
  imports: [LocalDatePipe, ChatTranscript, ChatComposer],
  templateUrl: './advisor-page.html',
  styleUrl: './advisor-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdvisorPage implements OnInit, OnDestroy {
  protected readonly advisor = inject(AdvisorService);
  protected readonly realtime = inject(RealtimeService);
  protected readonly reasons = REASONS;

  constructor() {
    effect(() => {
      this.advisor.messages();
      if (!document.hidden) {
        this.advisor.markRead();
      }
    });
  }

  ngOnInit(): void {
    this.advisor.enter();
  }

  ngOnDestroy(): void {
    this.advisor.leave();
  }

  protected reason(conversation: AdvisorConversation): string {
    return conversation.handoffReason ? REASONS[conversation.handoffReason] : '';
  }

  /** Customers are shown by the end of their ID: advisors don't get banking data or names. */
  protected customer(conversation: AdvisorConversation): string {
    return `Customer …${conversation.customerId.slice(-6)}`;
  }
}
