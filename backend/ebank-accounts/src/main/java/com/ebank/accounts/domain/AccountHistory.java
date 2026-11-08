package com.ebank.accounts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One append-only record per modification of an account. */
@Entity
@Immutable
@Table(name = "account_history")
public class AccountHistory {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before")
    private AccountStatus statusBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_after", nullable = false)
    private AccountStatus statusAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "type_before")
    private AccountType typeBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "type_after", nullable = false)
    private AccountType typeAfter;

    @Column(name = "changed_by", nullable = false)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected AccountHistory() {}

    public AccountHistory(
            Account account, AccountAction action, AccountStatus statusBefore, AccountType typeBefore, String changedBy) {
        this.id = UUID.randomUUID();
        this.accountId = account.getAccountId();
        this.action = action;
        this.statusBefore = statusBefore;
        this.statusAfter = account.getStatus();
        this.typeBefore = typeBefore;
        this.typeAfter = account.getType();
        this.changedBy = changedBy;
        this.changedAt = Instant.now();
    }

    public AccountAction getAction() {
        return action;
    }

    public AccountStatus getStatusBefore() {
        return statusBefore;
    }

    public AccountStatus getStatusAfter() {
        return statusAfter;
    }

    public AccountType getTypeBefore() {
        return typeBefore;
    }

    public AccountType getTypeAfter() {
        return typeAfter;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
