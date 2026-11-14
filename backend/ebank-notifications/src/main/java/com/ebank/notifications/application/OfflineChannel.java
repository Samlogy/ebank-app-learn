package com.ebank.notifications.application;

import com.ebank.notifications.domain.Notification;

/** A way to reach a user who is not connected: email or SMS. */
public interface OfflineChannel {

    enum Kind { EMAIL, SMS }

    Kind kind();

    /** Throws if sending failed, so the outbox retries. */
    void send(Notification notification, String address);
}
