package com.ebank.chatbot.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable except for soft deletion, which blanks the content. */
@Entity
@Table(name = "message")
public class ChatMessage {

    static final String DELETED_CONTENT = "";

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(nullable = false, updatable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, updatable = false)
    private SenderType senderType;

    @Column(name = "sender_id", updatable = false)
    private String senderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MessageType type;

    @Column(nullable = false, length = 4000)
    private String content;

    @Column(name = "client_message_id", updatable = false)
    private String clientMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ChatMessage() {}

    ChatMessage(UUID conversationId, long seq, SenderType senderType, String senderId, MessageType type,
            String content, String clientMessageId, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.conversationId = conversationId;
        this.seq = seq;
        this.senderType = senderType;
        this.senderId = senderId;
        this.type = type;
        this.content = content;
        this.clientMessageId = clientMessageId;
        this.createdAt = createdAt;
    }

    void delete(Instant now) {
        if (deletedAt == null) {
            deletedAt = now;
            content = DELETED_CONTENT;
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() { return id; }
    public UUID getConversationId() { return conversationId; }
    public long getSeq() { return seq; }
    public SenderType getSenderType() { return senderType; }
    public String getSenderId() { return senderId; }
    public MessageType getType() { return type; }
    public String getContent() { return content; }
    public String getClientMessageId() { return clientMessageId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
