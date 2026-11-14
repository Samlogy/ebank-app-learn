package com.ebank.realtime.api;

import java.util.Set;

/**
 * Pushes to connected users. Best effort: nothing is queued for users who are offline, so callers
 * store what matters first and use {@link #isSubscribed} to decide on another channel.
 *
 * <p>Only knows this instance's sessions. Several instances need a broker relay and a shared registry.
 */
public interface RealtimeMessaging {

    void send(String userId, UserQueue queue, Object payload);

    /** Whether the user has at least one session subscribed to the queue right now. */
    boolean isSubscribed(String userId, UserQueue queue);

    /** User IDs subscribed to the queue whose token carries the realm role, such as {@code ADVISOR}. */
    Set<String> subscribedUsersWithRole(UserQueue queue, String role);
}
