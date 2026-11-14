package com.ebank.notifications.application;

/** Whether a user has a live connection that can receive a push right now. */
public interface Presence {

    boolean isOnline(String userId);
}
