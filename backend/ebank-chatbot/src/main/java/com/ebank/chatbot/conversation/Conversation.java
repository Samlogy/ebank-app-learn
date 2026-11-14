package com.ebank.chatbot.conversation;

import com.ebank.common.error.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** One support conversation. Every state change goes through a method here. */
@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "handoff_reason")
    private HandoffReason handoffReason;

    @Column(name = "advisor_id")
    private String advisorId;

    @Column(length = 1000)
    private String summary;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "handoff_at")
    private Instant handoffAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Conversation() {}

    public Conversation(String customerId, Instant now) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.state = ConversationState.BOT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** The sequence number for the next message. Call with the row locked. */
    long nextSeq() {
        return ++lastSeq;
    }

    public boolean acceptsCustomerMessages() {
        return state != ConversationState.RESOLVED;
    }

    public void handoff(HandoffReason reason, Instant now) {
        require(state == ConversationState.BOT, "HANDOFF_NOT_ALLOWED", "The conversation is already with or waiting for an advisor.");
        state = ConversationState.WAITING_ADVISOR;
        handoffReason = reason;
        handoffAt = now;
        summary = null;
    }

    public void summarize(String text) {
        summary = text == null ? null : text.length() > 1000 ? text.substring(0, 1000) : text;
    }

    /** First come, first served: a second advisor gets a 409. */
    public void assign(String advisor, Instant now) {
        if (state != ConversationState.WAITING_ADVISOR) {
            throw ApiException.conflict("ALREADY_ASSIGNED", "This conversation is no longer waiting for an advisor.");
        }
        state = ConversationState.WITH_ADVISOR;
        advisorId = advisor;
        assignedAt = now;
    }

    /** From the queue, or after a resolution, the customer can talk to the assistant again. */
    public void backToBot() {
        require(state == ConversationState.WAITING_ADVISOR || state == ConversationState.RESOLVED,
                "BACK_TO_BOT_NOT_ALLOWED", "Only a waiting or resolved conversation can go back to the assistant.");
        state = ConversationState.BOT;
        advisorId = null;
        handoffReason = null;
        resolvedAt = null;
    }

    public void resolve(Instant now) {
        require(state != ConversationState.RESOLVED, "ALREADY_RESOLVED", "The conversation is already resolved.");
        state = ConversationState.RESOLVED;
        resolvedAt = now;
    }

    public boolean isAssignedTo(String userId) {
        return state == ConversationState.WITH_ADVISOR && userId.equals(advisorId);
    }

    /** Time spent in the queue before an advisor took it. */
    public Optional<Duration> queueWait() {
        return handoffAt == null || assignedAt == null ? Optional.empty() : Optional.of(Duration.between(handoffAt, assignedAt));
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    private static void require(boolean condition, String code, String message) {
        if (!condition) {
            throw ApiException.unprocessable(code, message);
        }
    }

    public UUID getId() { return id; }
    public String getCustomerId() { return customerId; }
    public ConversationState getState() { return state; }
    public HandoffReason getHandoffReason() { return handoffReason; }
    public String getAdvisorId() { return advisorId; }
    public String getSummary() { return summary; }
    public long getLastSeq() { return lastSeq; }
    public Instant getHandoffAt() { return handoffAt; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
