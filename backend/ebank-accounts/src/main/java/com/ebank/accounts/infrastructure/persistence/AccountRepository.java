package com.ebank.accounts.infrastructure.persistence;

import com.ebank.accounts.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByAccountId(String accountId);

    Page<Account> findByUserId(String userId, Pageable pageable);

    @Query("select a.accountId from Account a where a.userId = :userId")
    List<String> findAccountIdsByUserId(@Param("userId") String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountId = :accountId")
    Optional<Account> findByAccountIdForUpdate(@Param("accountId") String accountId);
}
