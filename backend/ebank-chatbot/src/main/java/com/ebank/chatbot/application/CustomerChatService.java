package com.ebank.chatbot.application;

import com.ebank.auth.api.CurrentUser;
import com.ebank.chatbot.ChatbotProperties;
import com.ebank.chatbot.audit.AuditLog;
import com.ebank.chatbot.conversation.ChatMessage;
import com.ebank.chatbot.conversation.Conversation;
import com.ebank.chatbot.conversation.ConversationState;
import com.ebank.chatbot.conversation.ConversationStore;
import com.ebank.chatbot.conversation.HandoffReason;
import com.ebank.chatbot.conversation.MessageType;
import com.ebank.chatbot.conversation.SenderType;
import com.ebank.chatbot.ratelimit.RateLimiter;
import com.ebank.common.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** The customer side. A customer only ever sees their own conversations: anything else is a 404. */
@Service
public class CustomerChatService {

    static final String BACK_TO_BOT_TEXT = "You're talking to the assistant again.";
    static final String RESOLVED_TEXT = "This conversation is closed. Start a new one any time.";

    private final ConversationStore store;
    private final AssistantRunner runner;
    private final HandoffService handoffs;
    private final AuditLog audit;
    private final RateLimiter limiter;
    private final CurrentUser currentUser;
    private final ChatbotProperties.Limits limits;
    private final TransactionTemplate tx;

    CustomerChatService(ConversationStore store, AssistantRunner runner, HandoffService handoffs, AuditLog audit,
            RateLimiter limiter, CurrentUser currentUser, ChatbotProperties properties, PlatformTransactionManager transactions) {
        this.store = store;
        this.runner = runner;
        this.handoffs = handoffs;
        this.audit = audit;
        this.limiter = limiter;
        this.currentUser = currentUser;
        this.limits = properties.limits();
        this.tx = new TransactionTemplate(transactions);
    }

    public record Sent(ChatMessage message, boolean replayed) {}

    /** Returns the customer's open conversation, or starts one. */
    public Conversation open() {
        String userId = currentUser.userId();
        Optional<Conversation> existing = store.findOpen(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        limiter.require("chat-open:" + userId, limits.conversationsPerHour(), Duration.ofHours(1));
        try {
            return tx.execute(status -> store.create(userId));
        } catch (DataIntegrityViolationException ex) {
            // Two tabs opened the chat at the same time: the unique index kept one.
            return store.findOpen(userId).orElseThrow(() -> ex);
        }
    }

    @Transactional(readOnly = true)
    public Page<Conversation> list(Pageable pageable) {
        return store.ofCustomer(currentUser.userId(), pageable);
    }

    @Transactional(readOnly = true)
    public Conversation get(UUID id) {
        return owned(store.find(id));
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> messages(UUID id, long afterSeq, int limit) {
        owned(store.find(id));
        return store.after(id, afterSeq, limit);
    }

    /** Idempotent on {@code clientMessageId}: a retry returns the stored message. */
    @Transactional
    public Sent send(UUID id, String clientMessageId, String text) {
        String userId = currentUser.userId();
        Conversation locked = owned(Optional.of(store.lock(id)));
        Optional<ChatMessage> earlier = store.findByClientId(id, userId, clientMessageId);
        if (earlier.isPresent()) {
            return new Sent(earlier.get(), true);
        }
        limiter.require("chat-message:" + userId, limits.messagesPerMinute(), Duration.ofMinutes(1));
        if (!locked.acceptsCustomerMessages()) {
            throw ApiException.unprocessable("CONVERSATION_RESOLVED", "This conversation is closed. Start a new one.");
        }
        ChatMessage message = store.append(locked, SenderType.CUSTOMER, userId, MessageType.TEXT, MessageText.clean(text), clientMessageId);
        if (locked.getState() == ConversationState.BOT) {
            runner.replyAfterCommit(id, userId, message.getSeq());
        }
        return new Sent(message, false);
    }

    @Transactional
    public Conversation handoff(UUID id) {
        String userId = currentUser.userId();
        Conversation locked = owned(Optional.of(store.lock(id)));
        limiter.require("chat-handoff:" + userId, limits.handoffsPerHour(), Duration.ofHours(1));
        handoffs.handoff(locked, HandoffReason.USER_REQUEST, userId);
        return locked;
    }

    @Transactional
    public Conversation backToBot(UUID id) {
        String userId = currentUser.userId();
        Conversation locked = owned(Optional.of(store.lock(id)));
        if (locked.getState() == ConversationState.RESOLVED && store.findOpen(userId).isPresent()) {
            throw ApiException.conflict("OTHER_CONVERSATION_OPEN", "Another conversation is already open.");
        }
        locked.backToBot();
        store.append(locked, SenderType.SYSTEM, null, MessageType.SYSTEM, BACK_TO_BOT_TEXT, null);
        audit.record(userId, id, AuditLog.Action.BACK_TO_BOT, null);
        store.publishState(locked);
        handoffs.notifyAdvisors(locked);
        return locked;
    }

    @Transactional
    public Conversation resolve(UUID id) {
        String userId = currentUser.userId();
        Conversation locked = owned(Optional.of(store.lock(id)));
        boolean wasQueued = locked.getState() == ConversationState.WAITING_ADVISOR;
        locked.resolve(Instant.now());
        store.append(locked, SenderType.SYSTEM, null, MessageType.SYSTEM, RESOLVED_TEXT, null);
        audit.record(userId, id, AuditLog.Action.RESOLVE, "customer");
        store.publishState(locked);
        if (wasQueued) {
            handoffs.notifyAdvisors(locked);
        }
        return locked;
    }

    @Transactional
    public void markRead(UUID id, long seq) {
        Conversation locked = owned(Optional.of(store.lock(id)));
        store.markRead(locked, currentUser.userId(), seq);
    }

    @Transactional
    public ChatMessage delete(UUID id, UUID messageId) {
        String userId = currentUser.userId();
        Conversation locked = owned(Optional.of(store.lock(id)));
        ChatMessage deleted = store.delete(locked, messageId, userId);
        audit.record(userId, id, AuditLog.Action.MESSAGE_DELETED, null);
        return deleted;
    }

    private Conversation owned(Optional<Conversation> conversation) {
        String userId = currentUser.userId();
        return conversation.filter(c -> c.getCustomerId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("CONVERSATION_NOT_FOUND", "Conversation not found."));
    }
}
