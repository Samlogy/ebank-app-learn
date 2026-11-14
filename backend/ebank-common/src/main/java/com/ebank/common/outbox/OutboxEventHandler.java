package com.ebank.common.outbox;

import java.util.UUID;

/**
 * Receives one type of outbox event. Delivery is at least once: an event can be handled again
 * after a crash or a failing handler of the same type, so handlers must be idempotent (for
 * example, keyed on {@code eventId}).
 *
 * <p>Runs inside the relay's transaction. Throwing rolls back the handler's writes and schedules
 * a retry with backoff.
 */
public interface OutboxEventHandler<T> {

    /** The {@link Outbox#append} type this handler consumes. */
    String eventType();

    Class<T> payloadType();

    void handle(UUID eventId, T payload);
}
