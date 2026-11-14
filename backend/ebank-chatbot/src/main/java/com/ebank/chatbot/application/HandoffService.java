package com.ebank.chatbot.application;

import com.ebank.chatbot.ChatbotProperties;
import com.ebank.chatbot.api.HandoffRequested;
import com.ebank.chatbot.assistant.Assistant;
import com.ebank.chatbot.audit.AuditLog;
import com.ebank.chatbot.conversation.Conversation;
import com.ebank.chatbot.conversation.ConversationState;
import com.ebank.chatbot.conversation.ConversationStore;
import com.ebank.chatbot.conversation.HandoffReason;
import com.ebank.chatbot.conversation.MessageType;
import com.ebank.chatbot.conversation.Participant;
import com.ebank.chatbot.conversation.SenderType;
import com.ebank.chatbot.conversation.Views.ChatEvent;
import com.ebank.chatbot.realtime.ChatPublisher;
import com.ebank.common.error.ApiException;
import com.ebank.common.outbox.Outbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves conversations from the assistant to people. On handoff the conversation joins the queue;
 * after commit it is assigned to the online advisor with the fewest open conversations. With no
 * advisor online, the customer is told so, the conversation stays queued, and advisors are alerted
 * by email (through notifications). Advisors can also take one from the queue themselves.
 */
@Service
public class HandoffService {

    static final String QUEUED_TEXT = "I'm passing you to an advisor. Stay here: your conversation continues in this window.";
    static final String NO_ADVISOR_TEXT = "No advisor is online right now. Your request stays in the queue and an advisor "
            + "will answer here as soon as possible.";
    static final String JOINED_TEXT = "An advisor joined the conversation.";

    private static final Logger log = LoggerFactory.getLogger(HandoffService.class);

    private final ConversationStore store;
    private final ChatPublisher publisher;
    private final Assistant assistant;
    private final Outbox outbox;
    private final AuditLog audit;
    private final ExecutorService executor;
    private final TransactionTemplate tx;
    private final ChatbotProperties properties;
    private final MeterRegistry meters;
    private final Timer queueWait;

    HandoffService(ConversationStore store, ChatPublisher publisher, Assistant assistant, Outbox outbox, AuditLog audit,
            ExecutorService chatbotExecutor, PlatformTransactionManager transactions, ChatbotProperties properties,
            MeterRegistry meters) {
        this.store = store;
        this.publisher = publisher;
        this.assistant = assistant;
        this.outbox = outbox;
        this.audit = audit;
        this.executor = chatbotExecutor;
        this.tx = new TransactionTemplate(transactions);
        this.properties = properties;
        this.meters = meters;
        this.queueWait = Timer.builder("ebank.chatbot.queue.wait")
                .description("Time a conversation waits before an advisor takes it").register(meters);
    }

    /** @param actorId the customer, or null when the assistant escalated */
    @Transactional(propagation = Propagation.MANDATORY)
    public void handoff(Conversation locked, HandoffReason reason, String actorId) {
        locked.handoff(reason, Instant.now());
        store.append(locked, SenderType.SYSTEM, null, MessageType.SYSTEM, QUEUED_TEXT, null);
        audit.record(actorId, locked.getId(), AuditLog.Action.HANDOFF, reason.name());
        Counter.builder("ebank.chatbot.handoffs").description("Conversations handed to advisors")
                .tag("reason", reason.name()).register(meters).increment();
        store.publishState(locked);
        notifyAdvisors(locked);
        UUID id = locked.getId();
        afterCommit(() -> executor.submit(() -> {
            summarize(id);
            autoAssign(id);
        }));
    }

    /** First come, first served. A conversation already taken gives a 409. */
    @Transactional
    public Conversation claim(UUID conversationId, String advisorId) {
        Conversation locked = store.lock(conversationId);
        locked.assign(advisorId, Instant.now());
        store.join(locked, advisorId, Participant.Role.ADVISOR);
        store.append(locked, SenderType.SYSTEM, null, MessageType.SYSTEM, JOINED_TEXT, null);
        audit.record(advisorId, conversationId, AuditLog.Action.ASSIGN, null);
        locked.queueWait().ifPresent(queueWait::record);
        store.publishState(locked);
        notifyAdvisors(locked);
        return locked;
    }

    void autoAssign(UUID conversationId) {
        Set<String> online = publisher.onlineAdvisors();
        if (online.isEmpty()) {
            tx.executeWithoutResult(status -> {
                Conversation locked = store.lock(conversationId);
                if (locked.getState() == ConversationState.WAITING_ADVISOR) {
                    store.append(locked, SenderType.SYSTEM, null, MessageType.SYSTEM, NO_ADVISOR_TEXT, null);
                    outbox.append(HandoffRequested.TYPE, conversationId.toString(), new HandoffRequested(
                            conversationId, locked.getHandoffReason().name(), Instant.now()));
                }
            });
            return;
        }
        String advisor = online.stream()
                .min(Comparator.comparingLong(store::activeCount).thenComparing(Comparator.naturalOrder()))
                .orElseThrow();
        try {
            tx.executeWithoutResult(status -> claimIfWaiting(conversationId, advisor));
        } catch (ApiException | OptimisticLockingFailureException ex) {
            log.debug("Auto-assignment of {} skipped: {}", conversationId, ex.getClass().getSimpleName());
        }
    }

    private void claimIfWaiting(UUID conversationId, String advisor) {
        if (store.find(conversationId).map(c -> c.getState() == ConversationState.WAITING_ADVISOR).orElse(false)) {
            claim(conversationId, advisor);
            log.info("Conversation {} assigned automatically", conversationId);
        }
    }

    /** The advisor's handover note. Best effort: an advisor can always read the transcript. */
    void summarize(UUID conversationId) {
        try {
            String summary = assistant.summarize(History.of(store.latest(conversationId, properties.assistant().historySize())));
            if (summary == null) {
                return;
            }
            tx.executeWithoutResult(status -> {
                Conversation locked = store.lock(conversationId);
                if (locked.getState() == ConversationState.WAITING_ADVISOR || locked.getState() == ConversationState.WITH_ADVISOR) {
                    locked.summarize(summary);
                }
            });
        } catch (RuntimeException ex) {
            log.warn("Summary for conversation {} failed: {}", conversationId, ex.getClass().getSimpleName());
        }
    }

    /** Advisors with the chat open reload their queue when something changes in it. */
    void notifyAdvisors(Conversation conversation) {
        publisher.afterCommit(publisher.onlineAdvisors(), ChatEvent.queue(conversation.getId(), conversation.getState()));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
