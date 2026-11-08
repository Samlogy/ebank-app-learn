package com.ebank.transactions.infrastructure.persistence;

import com.ebank.transactions.domain.Transfer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TransferRepository extends JpaRepository<Transfer, UUID>, JpaSpecificationExecutor<Transfer> {

    Optional<Transfer> findByInitiatedByAndIdempotencyKey(String initiatedBy, String idempotencyKey);
}
