package com.ebank.chatbot.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Who is in a conversation, and how far they have read (read receipts). */
@Entity
@Table(name = "conversation_participant")
public class Participant {

    public enum Role { CUSTOMER, ADVISOR }

    @Embeddable
    public record Key(@Column(name = "conversation_id") UUID conversationId, @Column(name = "user_id") String userId)
            implements Serializable {}

    @EmbeddedId
    private Key key;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Role role;

    @Column(name = "last_read_seq", nullable = false)
    private long lastReadSeq;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected Participant() {}

    Participant(UUID conversationId, String userId, Role role, Instant now) {
        this.key = new Key(conversationId, userId);
        this.role = role;
        this.joinedAt = now;
    }

    /** Read pointers only move forward. */
    boolean markRead(long seq) {
        if (seq > lastReadSeq) {
            lastReadSeq = seq;
            return true;
        }
        return false;
    }

    public String getUserId() { return key.userId(); }
    public Role getRole() { return role; }
    public long getLastReadSeq() { return lastReadSeq; }
}
