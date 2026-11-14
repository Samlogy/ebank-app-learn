package com.ebank.chatbot.conversation;

import com.ebank.chatbot.conversation.Views.ChatEvent;
import com.ebank.chatbot.realtime.ChatPublisher;
import com.ebank.common.error.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence and live delivery for conversations, shared by the customer, assistant and advisor
 * sides. Every message goes through {@link #append}, which needs the conversation locked.
 */
@Component
public class ConversationStore {

    static final String WELCOME = "Hello, I'm the EBANK assistant. I can tell you about your accounts, balances and "
            + "recent transactions. I can't move money. Type \"advisor\" at any time to talk to a person.";

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ParticipantRepository participants;
    private final ChatPublisher publisher;
    private final MeterRegistry meters;

    ConversationStore(ConversationRepository conversations, MessageRepository messages, ParticipantRepository participants,
            ChatPublisher publisher, MeterRegistry meters) {
        this.conversations = conversations;
        this.messages = messages;
        this.participants = participants;
        this.publisher = publisher;
        this.meters = meters;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Conversation create(String customerId) {
        Instant now = Instant.now();
        Conversation conversation = conversations.saveAndFlush(new Conversation(customerId, now));
        participants.save(new Participant(conversation.getId(), customerId, Participant.Role.CUSTOMER, now));
        append(conversation, SenderType.SYSTEM, null, MessageType.SYSTEM, WELCOME, null);
        return conversation;
    }

    public Optional<Conversation> findOpen(String customerId) {
        return conversations.findOpenByCustomer(customerId);
    }

    public Optional<Conversation> find(UUID id) {
        return conversations.findById(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Conversation lock(UUID id) {
        return conversations.findForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("CONVERSATION_NOT_FOUND", "Conversation not found."));
    }

    public Page<Conversation> ofCustomer(String customerId, Pageable pageable) {
        return conversations.findByCustomerId(customerId, pageable);
    }

    public List<Conversation> queue(int limit) {
        return conversations.findByStateOrderByHandoffAtAsc(ConversationState.WAITING_ADVISOR, PageRequest.of(0, limit));
    }

    public List<Conversation> assignedTo(String advisorId) {
        return conversations.findByStateAndAdvisorIdOrderByAssignedAtAsc(ConversationState.WITH_ADVISOR, advisorId);
    }

    public long activeCount(String advisorId) {
        return conversations.countByStateAndAdvisorId(ConversationState.WITH_ADVISOR, advisorId);
    }

    /** Adds the message with the next sequence number and pushes it to both sides after commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ChatMessage append(Conversation locked, SenderType sender, String senderId, MessageType type, String content, String clientMessageId) {
        ChatMessage message = messages.save(new ChatMessage(
                locked.getId(), locked.nextSeq(), sender, senderId, type, content, clientMessageId, Instant.now()));
        Counter.builder("ebank.chatbot.messages").description("Chat messages stored").tag("sender", sender.name())
                .register(meters).increment();
        publisher.afterCommit(recipients(locked), ChatEvent.message(message));
        return message;
    }

    public Optional<ChatMessage> findByClientId(UUID conversationId, String senderId, String clientMessageId) {
        return messages.findByConversationIdAndSenderIdAndClientMessageId(conversationId, senderId, clientMessageId);
    }

    public List<ChatMessage> after(UUID conversationId, long afterSeq, int limit) {
        return messages.findByConversationIdAndSeqGreaterThanOrderBySeqAsc(conversationId, afterSeq, PageRequest.of(0, limit));
    }

    /** The last {@code count} messages, oldest first. */
    public List<ChatMessage> latest(UUID conversationId, int count) {
        List<ChatMessage> newestFirst = new ArrayList<>(messages.findByConversationIdOrderBySeqDesc(conversationId, PageRequest.of(0, count)));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /** The sequence number of the last message from this kind of sender, or 0. */
    public long lastSeqOf(UUID conversationId, SenderType sender) {
        return messages.lastSeqOf(conversationId, sender).orElse(0L);
    }

    /** How far a participant has read, or 0. */
    public long lastReadSeq(UUID conversationId, String userId) {
        return participants.findById(new Participant.Key(conversationId, userId)).map(Participant::getLastReadSeq).orElse(0L);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void join(Conversation conversation, String userId, Participant.Role role) {
        Participant.Key key = new Participant.Key(conversation.getId(), userId);
        if (!participants.existsById(key)) {
            participants.save(new Participant(conversation.getId(), userId, role, Instant.now()));
        }
    }

    /** Moves the reader's pointer and tells the other side (read receipt). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markRead(Conversation conversation, String userId, long seq) {
        long upTo = Math.min(seq, conversation.getLastSeq());
        participants.findById(new Participant.Key(conversation.getId(), userId)).ifPresent(p -> {
            if (p.markRead(upTo)) {
                publisher.afterCommit(others(conversation, userId), ChatEvent.read(conversation.getId(), upTo));
            }
        });
    }

    public List<Participant> participants(UUID conversationId) {
        return participants.findByKeyConversationId(conversationId);
    }

    /** Only the sender can delete their message. Someone else's message gets the same 404 as a missing one. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ChatMessage delete(Conversation conversation, UUID messageId, String userId) {
        ChatMessage message = messages.findByIdAndConversationId(messageId, conversation.getId())
                .filter(m -> userId.equals(m.getSenderId()))
                .orElseThrow(() -> ApiException.notFound("MESSAGE_NOT_FOUND", "Message not found."));
        message.delete(Instant.now());
        publisher.afterCommit(recipients(conversation), ChatEvent.deleted(message));
        return message;
    }

    public void publishState(Conversation conversation) {
        publisher.afterCommit(recipients(conversation), ChatEvent.state(conversation));
    }

    public void publishTyping(Conversation conversation, boolean typing) {
        publisher.afterCommit(List.of(conversation.getCustomerId()), ChatEvent.typing(conversation.getId(), typing));
    }

    private static List<String> recipients(Conversation c) {
        List<String> users = new ArrayList<>(2);
        users.add(c.getCustomerId());
        if (c.getAdvisorId() != null) {
            users.add(c.getAdvisorId());
        }
        return users;
    }

    private static List<String> others(Conversation c, String userId) {
        return recipients(c).stream().filter(u -> !u.equals(userId)).toList();
    }
}
