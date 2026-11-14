package com.ebank.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Something that happened to a user. Only the read state changes after creation. */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationType type;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    /** The transaction ID for transfer notifications. */
    @Column(name = "source_id", updatable = false)
    private String sourceId;

    /** The user's own account concerned. */
    @Column(name = "account_id", updatable = false)
    private String accountId;

    /** The other account, when there is one. */
    @Column(updatable = false)
    private String counterparty;

    @Column(updatable = false, precision = 30, scale = 8)
    private BigDecimal amount;

    @Column(updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {}

    public Notification(
            String userId,
            NotificationType type,
            UUID sourceEventId,
            String sourceId,
            String accountId,
            String counterparty,
            BigDecimal amount,
            String currency,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.type = type;
        this.sourceEventId = sourceEventId;
        this.sourceId = sourceId;
        this.accountId = accountId;
        this.counterparty = counterparty;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    /** Marking twice keeps the first time. */
    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    public boolean isRead() {
        return readAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
