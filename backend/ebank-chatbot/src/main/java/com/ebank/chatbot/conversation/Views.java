package com.ebank.chatbot.conversation;

import java.time.Instant;
import java.util.UUID;

/** JSON shapes for the API and the live events. */
public final class Views {

    private Views() {}

    /** No sender ID: customers don't need an advisor's account ID, and advisors see the customer's elsewhere. */
    public record MessageView(
            UUID id, long seq, SenderType senderType, MessageType type, String content,
            String clientMessageId, Instant createdAt, boolean deleted) {

        public static MessageView of(ChatMessage m) {
            return new MessageView(m.getId(), m.getSeq(), m.getSenderType(), m.getType(), m.getContent(),
                    m.getClientMessageId(), m.getCreatedAt(), m.isDeleted());
        }
    }

    public record CustomerConversationView(
            UUID id, ConversationState state, HandoffReason handoffReason, long lastSeq, Instant createdAt, Instant updatedAt) {

        public static CustomerConversationView of(Conversation c) {
            return new CustomerConversationView(c.getId(), c.getState(), c.getHandoffReason(), c.getLastSeq(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    /** What an advisor sees: who, why, since when, and the assistant's summary of the request. */
    public record AdvisorConversationView(
            UUID id, String customerId, ConversationState state, HandoffReason handoffReason, String summary,
            long lastSeq, Instant handoffAt, Instant assignedAt) {

        public static AdvisorConversationView of(Conversation c) {
            return new AdvisorConversationView(c.getId(), c.getCustomerId(), c.getState(), c.getHandoffReason(),
                    c.getSummary(), c.getLastSeq(), c.getHandoffAt(), c.getAssignedAt());
        }
    }

    /**
     * Pushed on {@code /user/queue/chat}. {@code type} is MESSAGE, STATE, READ, DELETED, TYPING or QUEUE.
     * Only the fields that apply to the type are set.
     */
    public record ChatEvent(
            String type, UUID conversationId, MessageView message, ConversationState state, Long readSeq, Boolean typing) {

        public static ChatEvent message(ChatMessage m) {
            return new ChatEvent("MESSAGE", m.getConversationId(), MessageView.of(m), null, null, null);
        }

        public static ChatEvent deleted(ChatMessage m) {
            return new ChatEvent("DELETED", m.getConversationId(), MessageView.of(m), null, null, null);
        }

        public static ChatEvent state(Conversation c) {
            return new ChatEvent("STATE", c.getId(), null, c.getState(), null, null);
        }

        public static ChatEvent read(UUID conversationId, long seq) {
            return new ChatEvent("READ", conversationId, null, null, seq, null);
        }

        public static ChatEvent typing(UUID conversationId, boolean typing) {
            return new ChatEvent("TYPING", conversationId, null, null, null, typing);
        }

        /** Tells advisors the queue changed; they reload it. */
        public static ChatEvent queue(UUID conversationId, ConversationState state) {
            return new ChatEvent("QUEUE", conversationId, null, state, null, null);
        }
    }
}
