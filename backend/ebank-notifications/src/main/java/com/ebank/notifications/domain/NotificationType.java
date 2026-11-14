package com.ebank.notifications.domain;

public enum NotificationType {
    /** Money arrived on one of the user's accounts from someone else, or from outside the bank. */
    TRANSFER_RECEIVED,
    /** The user sent money to someone else. */
    TRANSFER_SENT,
    /** The user moved money between two of their own accounts. */
    TRANSFER_INTERNAL,
    /** A transfer the user started was refused, for example for insufficient funds. */
    TRANSFER_CANCELLED
}
