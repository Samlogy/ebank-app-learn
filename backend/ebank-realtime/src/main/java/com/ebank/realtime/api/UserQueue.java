package com.ebank.realtime.api;

/**
 * A per-user queue a module pushes to, such as {@code /queue/notifications}. Declare one as a bean:
 * clients may then subscribe to {@code /user<destination>}, and nothing else is allowed.
 * Spring resolves {@code /user/...} per session, so a user only ever receives their own messages.
 */
public record UserQueue(String destination) {

    public UserQueue {
        if (destination == null || !destination.matches("/queue/[a-z][a-z0-9-]{0,40}")) {
            throw new IllegalArgumentException("A user queue looks like /queue/<name>: " + destination);
        }
    }

    /** What clients subscribe to. */
    public String subscription() {
        return "/user" + destination;
    }
}
