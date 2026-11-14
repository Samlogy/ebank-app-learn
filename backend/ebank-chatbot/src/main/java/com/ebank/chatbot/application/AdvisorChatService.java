package com.ebank.chatbot.application;

import com.ebank.auth.api.CurrentUser;
import com.ebank.chatbot.ChatbotProperties;
import com.ebank.chatbot.api.AdvisorMessageSent;
import com.ebank.chatbot.audit.AuditLog;
import com.ebank.chatbot.conversation.ChatMessage;
import com.ebank.chatbot.conversation.Conversation;
import com.ebank.chatbot.conversation.ConversationState;
import com.ebank.chatbot.conversation.ConversationStore;
import com.ebank.chatbot.conversation.MessageType;
import com.ebank.chatbot.conversation.SenderType;
import com.ebank.chatbot.ratelimit.RateLimiter;
import com.ebank.chatbot.realtime.ChatPublisher;
import com.ebank.common.error.ApiException;
import com.ebank.common.outbox.Outbox;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The advisor side. Requires the ADVISOR role. An advisor sees the queue and the conversations
 * assigned to them, and nothing else; they have no access to banking data beyond what the
 * transcript shows.
 */
@Service
public class AdvisorChatService {

    public static final String ROLE = "ADVISOR";
    static final String RESOLVED_TEXT = "The advisor closed this conversation. Start a new one any time.";
    static final int QUEUE_LIMIT = 50;

    private final ConversationStore store;
    private final HandoffService handoffs;
    private final ChatPublisher publisher;
    private final Outbox outbox;
    private final AuditLog audit;
    private final RateLimiter limiter;
    private final CurrentUser currentUser;
    private final ChatbotProperties.Limits limits;

    AdvisorChatService(ConversationStore store, HandoffService handoffs, ChatPublisher publisher, Outbox outbox, AuditLog audit,
            RateLimiter limiter, CurrentUser currentUser, ChatbotProperties properties) {
        this.store = store;
        this.handoffs = handoffs;
        this.publisher = publisher;
        this.outbox = outbox;
        this.audit = audit;
        this.limiter = limiter;
        this.currentUser = currentUser;
        this.limits = properties.limits();
    }

    /** Oldest first. */
    @Transactional(readOnly = true)
    public List<Conversation> queue() {
        advisor();
        return store.queue(QUEUE_LIMIT);
    }

    @Transactional(readOnly = true)
    public List<Conversation> mine() {
        return store.assignedTo(advisor());
    }

    @Transactional(readOnly = true)
    public Conversation get(UUID id) {
        return visible(store.find(id), advisor());
    }

    /** The whole transcript, including the assistant part. */
    @Transactional(readOnly = true)
    public List<ChatMessage> messages(UUID id, long afterSeq, int limit) {
        visible(store.find(id), advisor());
        return store.after(id, afterSeq, limit);
    }

    public Conversation claim(UUID id) {
        String advisor = advisor();
        visible(store.find(id), advisor);
        return handoffs.claim(id, advisor);
    }

    @Transactional
    public CustomerChatService.Sent send(UUID id, String clientMessageId, String text) {
        String advisor = advisor();
        Conversation locked = assigned(store.lock(id), advisor);
        Optional<ChatMessage> earlier = store.findByClientId(id, advisor, clientMessageId);
        if (earlier.isPresent()) {
            return new CustomerChatService.Sent(earlier.get(), true);
        }
        limiter.require("chat-message:" + advisor, limits.messagesPerMinute(), Duration.ofMinutes(1));
        long previousAdvisorSeq = store.lastSeqOf(id, SenderType.ADVISOR);
        ChatMessage message = store.append(locked, SenderType.ADVISOR, advisor, MessageType.TEXT, MessageText.clean(text), clientMessageId);
        // Tell a customer who is away, once per batch of unread advisor messages.
        boolean alreadyUnread = previousAdvisorSeq > store.lastReadSeq(id, locked.getCustomerId());
        if (!publisher.isInChat(locked.getCustomerId()) && !alreadyUnread) {
            outbox.append(AdvisorMessageSent.TYPE, id.toString(), new AdvisorMessageSent(id, locked.getCustomerId(), Instant.now()));
        }
        return new CustomerChatService.Sent(message, false);
    }

    @Transactional
    public Conversation resolve(UUID id) {
        String advisor = advisor();
        Conversation locked = assigned(store.lock(id), advisor);
        locked.resolve(Instant.now());
        store.append(locked, SenderType.SYSTEM, null, MessageType.SYSTEM, RESOLVED_TEXT, null);
        audit.record(advisor, id, AuditLog.Action.RESOLVE, "advisor");
        store.publishState(locked);
        return locked;
    }

    @Transactional
    public void markRead(UUID id, long seq) {
        String advisor = advisor();
        store.markRead(assigned(store.lock(id), advisor), advisor, seq);
    }

    private String advisor() {
        currentUser.requireRole(ROLE);
        return currentUser.userId();
    }

    /** Waiting in the queue, or assigned to this advisor. Anything else is a 404. */
    private static Conversation visible(Optional<Conversation> conversation, String advisor) {
        return conversation
                .filter(c -> c.getState() == ConversationState.WAITING_ADVISOR || advisor.equals(c.getAdvisorId()))
                .orElseThrow(() -> ApiException.notFound("CONVERSATION_NOT_FOUND", "Conversation not found."));
    }

    private static Conversation assigned(Conversation conversation, String advisor) {
        Conversation c = visible(Optional.of(conversation), advisor);
        if (!c.isAssignedTo(advisor)) {
            throw ApiException.unprocessable("NOT_ASSIGNED", "Take the conversation before writing in it.");
        }
        return c;
    }
}
