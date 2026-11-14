package com.ebank.chatbot.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ebank.common.error.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RateLimiterTest {

    @Test
    void inMemoryLimitIsPerKey() {
        RateLimiter limiter = new RateLimiterConfig.InMemory();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("alice", 3, Duration.ofHours(1))).isTrue();
        }
        assertThat(limiter.tryAcquire("alice", 3, Duration.ofHours(1))).isFalse();
        assertThat(limiter.tryAcquire("bob", 3, Duration.ofHours(1))).isTrue();
        assertThatThrownBy(() -> limiter.require("alice", 3, Duration.ofHours(1)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(429));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisDownFailsOpen() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(new RateLimiterConfig.Redis(redis).tryAcquire("alice", 1, Duration.ofMinutes(1))).isTrue();
    }
}
