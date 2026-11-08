package com.ebank.transactions.domain;

import com.ebank.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One side of a completed transfer. Entries are never changed, and balances are derived from them. */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryType type;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {}

    public LedgerEntry(UUID transferId, String accountId, EntryType type, Money money) {
        this.id = UUID.randomUUID();
        this.transferId = transferId;
        this.accountId = accountId;
        this.type = type;
        this.amount = money.amount();
        this.currency = money.currency();
        this.createdAt = Instant.now();
    }

    public String getAccountId() {
        return accountId;
    }

    public EntryType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
