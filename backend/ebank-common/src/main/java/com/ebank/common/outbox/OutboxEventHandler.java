package com.ebank.common.outbox;

import java.util.UUID;
import org.springframework.util.ClassUtils;

/**
 * Receives one type of outbox event. Delivery is at least once: an event can be handled again
 * after a crash or a failing handler of the same type, so handlers must be idempotent (for
 * example, keyed on {@code eventId}).
 *
 * <p>Each handler runs in its own transaction. Throwing rolls back only this handler's writes
 * and schedules a retry; handlers of the same event that succeeded are not run again.
 */
public interface OutboxEventHandler<T> {

    /** The {@link Outbox#append} type this handler consumes. */
    String eventType();

    Class<T> payloadType();

    void handle(UUID eventId, T payload);

    /** Stored in {@code outbox_event_handled}, so it must stay stable once events are in flight. */
    default String handlerName() {
        return ClassUtils.getUserClass(this).getName();
    }
}
