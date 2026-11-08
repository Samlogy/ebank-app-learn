package com.ebank.accounts.domain;

import com.ebank.common.error.ApiException;
import com.ebank.common.money.Currencies;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Account() {}

    public Account(String accountId, AccountType type, String userId, String currency) {
        if (!Currencies.isSupported(currency)) {
            throw ApiException.unprocessable("UNSUPPORTED_CURRENCY", "This currency is not supported.");
        }
        requireCurrencyMatches(type, currency);
        this.id = UUID.randomUUID();
        this.accountId = accountId;
        this.type = type;
        this.status = AccountStatus.CREATED;
        this.userId = userId;
        this.currency = currency;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void activate() {
        moveTo(AccountStatus.ACTIVE);
    }

    public void disable() {
        moveTo(AccountStatus.DISABLED);
    }

    public void close() {
        moveTo(AccountStatus.CLOSED);
    }

    public void changeType(AccountType newType) {
        if (status == AccountStatus.CLOSED) {
            throw ApiException.conflict("INVALID_ACCOUNT_STATUS", "A closed account cannot be changed.");
        }
        requireCurrencyMatches(newType, currency);
        this.type = newType;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    private void moveTo(AccountStatus next) {
        if (!status.canBecome(next)) {
            throw ApiException.conflict("INVALID_ACCOUNT_STATUS", "An account cannot go from " + status + " to " + next + ".");
        }
        status = next;
    }

    private static void requireCurrencyMatches(AccountType type, String currency) {
        if ((type == AccountType.CRYPTO) != Currencies.isCrypto(currency)) {
            throw ApiException.unprocessable(
                    "CURRENCY_TYPE_MISMATCH", "CRYPTO accounts need a crypto currency, other accounts a fiat currency.");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public AccountType getType() {
        return type;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public String getUserId() {
        return userId;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
