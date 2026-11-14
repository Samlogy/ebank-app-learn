package com.ebank.chatbot.application;

import com.ebank.chatbot.ChatbotProperties;
import com.ebank.chatbot.assistant.Assistant;
import com.ebank.chatbot.assistant.AssistantUnavailableException;
import com.ebank.chatbot.audit.AuditLog;
import com.ebank.chatbot.conversation.Conversation;
import com.ebank.chatbot.conversation.ConversationState;
import com.ebank.chatbot.conversation.ConversationStore;
import com.ebank.chatbot.conversation.HandoffReason;
import com.ebank.chatbot.conversation.MessageType;
import com.ebank.chatbot.conversation.SenderType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Answers customer messages in the background, after they are committed. No database transaction
 * is open while the LLM thinks: history is read first, the reply is written after.
 *
 * <p>Replies for one conversation run one at a time. A run triggered by an older message is
 * skipped when a newer customer message exists, since that one's run answers both.
 */
@Component
class AssistantRunner {

    static final String UNAVAILABLE_TEXT = "Sorry, I can't answer right now. Try again in a moment, or type \"advisor\" to talk to a person.";
    private static final Logger log = LoggerFactory.getLogger(AssistantRunner.class);
    private static final int STRIPES = 64;

    private final ConversationStore store;
    private final Assistant assistant;
    private final HandoffService handoffs;
    private final AuditLog audit;
    private final ExecutorService executor;
    private final TransactionTemplate tx;
    private final ChatbotProperties properties;
    private final MeterRegistry meters;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    AssistantRunner(ConversationStore store, Assistant assistant, HandoffService handoffs, AuditLog audit,
            ExecutorService chatbotExecutor, PlatformTransactionManager transactions, ChatbotProperties properties,
            MeterRegistry meters) {
        this.store = store;
        this.assistant = assistant;
        this.handoffs = handoffs;
        this.audit = audit;
        this.executor = chatbotExecutor;
        this.tx = new TransactionTemplate(transactions);
        this.properties = properties;
        this.meters = meters;
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    void replyAfterCommit(UUID conversationId, String customerId, long triggerSeq) {
        Runnable task = () -> executor.submit(() -> run(conversationId, customerId, triggerSeq));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    void run(UUID conversationId, String customerId, long triggerSeq) {
        ReentrantLock lock = locks[Math.floorMod(conversationId.hashCode(), STRIPES)];
        lock.lock();
        Conversation conversation = null;
        try {
            conversation = store.find(conversationId).orElse(null);
            if (conversation == null || conversation.getState() != ConversationState.BOT
                    || store.lastSeqOf(conversationId, SenderType.CUSTOMER) > triggerSeq) {
                return;
            }
            store.publishTyping(conversation, true);
            Assistant.Reply reply;
            try {
                reply = assistant.reply(customerId, conversationId,
                        History.of(store.latest(conversationId, properties.assistant().historySize())));
                count("answered");
            } catch (AssistantUnavailableException ex) {
                log.warn("Assistant unavailable for conversation {}: {}", conversationId, ex.getMessage());
                count("unavailable");
                reply = new Assistant.Reply(UNAVAILABLE_TEXT, java.util.Optional.empty());
                audit.record(null, conversationId, AuditLog.Action.ASSISTANT_ERROR, "unavailable");
            }
            Assistant.Reply answer = reply;
            tx.executeWithoutResult(status -> {
                Conversation locked = store.lock(conversationId);
                // The customer may have asked for an advisor while the assistant was thinking.
                if (locked.getState() != ConversationState.BOT) {
                    return;
                }
                store.append(locked, SenderType.ASSISTANT, null, MessageType.TEXT, answer.text(), null);
                answer.escalationReason().ifPresent(reason -> handoffs.handoff(locked, HandoffReason.LLM_ESCALATION, null));
            });
        } catch (RuntimeException ex) {
            log.error("Assistant run failed for conversation {}", conversationId, ex);
            count("failed");
        } finally {
            if (conversation != null) {
                store.publishTyping(conversation, false);
            }
            lock.unlock();
        }
    }

    private void count(String outcome) {
        Counter.builder("ebank.chatbot.assistant.replies").description("Assistant replies by outcome")
                .tag("outcome", outcome).register(meters).increment();
    }
}
