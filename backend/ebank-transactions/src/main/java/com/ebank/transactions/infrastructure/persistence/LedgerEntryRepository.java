package com.ebank.transactions.infrastructure.persistence;

import com.ebank.transactions.domain.LedgerEntry;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query("""
            select coalesce(sum(case when e.type = com.ebank.transactions.domain.EntryType.CREDIT
                                     then e.amount else -e.amount end), 0.0)
            from LedgerEntry e
            where e.accountId = :accountId
            """)
    BigDecimal balanceOf(@Param("accountId") String accountId);
}
