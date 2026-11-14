package com.ebank.notifications.application;

/** Pushes a notification to every live connection of a user. Best effort: nothing is queued. */
public interface LivePublisher {

    void push(String userId, NotificationView notification);
}
