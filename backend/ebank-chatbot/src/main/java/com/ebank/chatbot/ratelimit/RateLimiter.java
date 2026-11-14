package com.ebank.chatbot.ratelimit;

import com.ebank.common.error.ApiException;
import java.time.Duration;
import org.springframework.http.HttpStatus;

/** Fixed-window counters per user and action. */
public interface RateLimiter {

    /** Counts one use and says whether it is within the limit. */
    boolean tryAcquire(String key, int limit, Duration window);

    default void require(String key, int limit, Duration window) {
        if (!tryAcquire(key, limit, window)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Wait a moment and try again.");
        }
    }
}
