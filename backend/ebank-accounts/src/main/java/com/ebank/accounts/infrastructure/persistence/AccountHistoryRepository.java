package com.ebank.accounts.infrastructure.persistence;

import com.ebank.accounts.domain.AccountHistory;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountHistoryRepository extends JpaRepository<AccountHistory, UUID> {

    Page<AccountHistory> findByAccountId(String accountId, Pageable pageable);
}
