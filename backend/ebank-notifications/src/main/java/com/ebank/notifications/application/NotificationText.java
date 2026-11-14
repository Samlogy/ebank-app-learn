package com.ebank.notifications.application;

import com.ebank.common.money.Money;
import com.ebank.notifications.domain.Notification;

/**
 * Plain text for email and SMS. It never contains a full account ID or the transfer reference,
 * because messages leave the bank's systems and the reference is free text from another person.
 */
public final class NotificationText {

    private NotificationText() {}

    public static String subject(Notification notification) {
        return switch (notification.getType()) {
            case TRANSFER_RECEIVED -> "You received " + amount(notification);
            case TRANSFER_SENT -> "You sent " + amount(notification);
            case TRANSFER_INTERNAL -> "You moved " + amount(notification) + " between your accounts";
            case TRANSFER_CANCELLED -> "Your transfer of " + amount(notification) + " was not made";
        };
    }

    public static String body(Notification notification) {
        String account = masked(notification.getAccountId());
        String detail = switch (notification.getType()) {
            case TRANSFER_RECEIVED -> amount(notification) + " arrived on your account " + account + ".";
            case TRANSFER_SENT -> amount(notification) + " left your account " + account
                    + " for the account " + masked(notification.getCounterparty()) + ".";
            case TRANSFER_INTERNAL -> amount(notification) + " moved from your account " + account
                    + " to your account " + masked(notification.getCounterparty()) + ".";
            case TRANSFER_CANCELLED -> "Your transfer of " + amount(notification) + " from your account " + account
                    + " was cancelled and no money moved. Check your balance and try again.";
        };
        return detail + "\n\nSign in to EBANK to see the details.\n\n"
                + "You get this message because you were offline. You can turn these messages off in your notification settings.";
    }

    /** Short enough for one SMS segment in most cases. */
    public static String sms(Notification notification) {
        return "EBANK: " + subject(notification) + ".";
    }

    static String amount(Notification notification) {
        try {
            return new Money(notification.getAmount(), notification.getCurrency()).amount().toPlainString()
                    + " " + notification.getCurrency();
        } catch (RuntimeException ex) {
            return notification.getAmount().stripTrailingZeros().toPlainString() + " " + notification.getCurrency();
        }
    }

    /** Keeps the last 4 characters: enough for the user to recognise the account. */
    static String masked(String accountId) {
        if (accountId == null) {
            return "(unknown)";
        }
        if (accountId.startsWith("EXTERNAL-")) {
            return "outside EBANK";
        }
        return accountId.length() <= 4 ? "••••" : "••••" + accountId.substring(accountId.length() - 4);
    }
}
