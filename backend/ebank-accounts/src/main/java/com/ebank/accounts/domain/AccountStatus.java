package com.ebank.accounts.domain;

/** CREATED -> ACTIVE <-> DISABLED -> CLOSED. CLOSED is final. */
public enum AccountStatus {
    CREATED,
    ACTIVE,
    DISABLED,
    CLOSED;

    boolean canBecome(AccountStatus next) {
        return switch (this) {
            case CREATED -> next == ACTIVE;
            case ACTIVE -> next == DISABLED;
            case DISABLED -> next == ACTIVE || next == CLOSED;
            case CLOSED -> false;
        };
    }
}
