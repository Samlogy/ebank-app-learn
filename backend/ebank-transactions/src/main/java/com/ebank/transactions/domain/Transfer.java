package com.ebank.transactions.domain;

import com.ebank.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "sender_account_id", nullable = false, updatable = false)
    private String senderAccountId;

    @Column(name = "receiver_account_id", nullable = false, updatable = false)
    private String receiverAccountId;

    @Column(nullable = false, updatable = false, precision = 30, scale = 8)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(updatable = false)
    private String reference;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "initiated_by", nullable = false, updatable = false)
    private String initiatedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Transfer() {}

    public Transfer(
            String senderAccountId,
            String receiverAccountId,
            Money money,
            String reference,
            String idempotencyKey,
            String initiatedBy) {
        this.id = UUID.randomUUID();
        this.transactionId = "TXN" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        this.senderAccountId = senderAccountId;
        this.receiverAccountId = receiverAccountId;
        this.amount = money.amount();
        this.currency = money.currency();
        this.reference = reference;
        this.idempotencyKey = idempotencyKey;
        this.initiatedBy = initiatedBy;
        this.status = TransferStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void complete() {
        requirePending();
        status = TransferStatus.DONE;
    }

    public void cancel(String reason) {
        requirePending();
        status = TransferStatus.CANCELLED;
        failureReason = reason;
    }

    /** Whether a retry with the same idempotency key is really the same request. */
    public boolean isSameRequest(String sender, String receiver, Money money, String otherReference) {
        return senderAccountId.equals(sender)
                && receiverAccountId.equals(receiver)
                && money().equals(money)
                && Objects.equals(reference, otherReference);
    }

    public Money money() {
        return new Money(amount, currency);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    private void requirePending() {
        if (status != TransferStatus.PENDING) {
            throw new IllegalStateException("Transfer is already " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getSenderAccountId() {
        return senderAccountId;
    }

    public String getReceiverAccountId() {
        return receiverAccountId;
    }

    public String getReference() {
        return reference;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
