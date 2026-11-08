package com.ebank.transactions.infrastructure.persistence;

import com.ebank.transactions.domain.Transfer;
import com.ebank.transactions.domain.TransferStatus;
import java.util.Set;
import org.springframework.data.jpa.domain.Specification;

public final class TransferSpecifications {

    private TransferSpecifications() {}

    /** Matches a field when a value is given. A null value matches everything. */
    public static Specification<Transfer> fieldEquals(String field, String value) {
        return (root, query, cb) -> value == null ? cb.conjunction() : cb.equal(root.get(field), value);
    }

    public static Specification<Transfer> involvesAccount(String accountId) {
        return (root, query, cb) -> accountId == null
                ? cb.conjunction()
                : cb.or(
                        cb.equal(root.get("senderAccountId"), accountId),
                        cb.equal(root.get("receiverAccountId"), accountId));
    }

    /**
     * A customer sees everything sent from their accounts, but only completed transfers received
     * by them: a cancelled transfer would reveal that the sender lacked funds.
     */
    public static Specification<Transfer> visibleToOwnerOf(Set<String> accountIds) {
        return (root, query, cb) -> {
            if (accountIds.isEmpty()) {
                return cb.disjunction();
            }
            return cb.or(
                    root.get("senderAccountId").in(accountIds),
                    cb.and(
                            root.get("receiverAccountId").in(accountIds),
                            cb.equal(root.get("status"), TransferStatus.DONE)));
        };
    }
}
