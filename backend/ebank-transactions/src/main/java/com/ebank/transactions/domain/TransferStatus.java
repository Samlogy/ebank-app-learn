package com.ebank.transactions.domain;

/** PENDING -> DONE or PENDING -> CANCELLED. DONE and CANCELLED are final. */
public enum TransferStatus {
    PENDING,
    DONE,
    CANCELLED
}
