package com.ebank.chatbot.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis by default, so limits hold across instances. {@code ebank.chatbot.rate-limit.store=memory}
 * keeps them in this JVM (tests, or a single instance without Redis).
 */
@Configuration(proxyBeanMethods = false)
class RateLimiterConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterConfig.class);

    @Bean
    RateLimiter rateLimiter(@Value("${ebank.chatbot.rate-limit.store:redis}") String store,
            org.springframework.beans.factory.ObjectProvider<StringRedisTemplate> redis) {
        if ("memory".equals(store)) {
            return new InMemory();
        }
        return new Redis(redis.getObject());
    }

    /**
     * Fails open: if Redis is down the chat keeps working without limits, and a warning is logged.
     * Denying every customer because the limiter is down would be worse than a brief lack of limits.
     */
    record Redis(StringRedisTemplate redis) implements RateLimiter {
        @Override
        public boolean tryAcquire(String key, int limit, Duration window) {
            String redisKey = "ebank:ratelimit:" + key + ":" + Instant.now().getEpochSecond() / window.toSeconds();
            try {
                Long count = redis.opsForValue().increment(redisKey);
                if (count != null && count == 1) {
                    redis.expire(redisKey, window);
                }
                return count == null || count <= limit;
            } catch (RuntimeException ex) {
                log.warn("Rate limiter unavailable, allowing the request: {}", ex.getClass().getSimpleName());
                return true;
            }
        }
    }

    static final class InMemory implements RateLimiter {
        private final Map<String, long[]> windows = new ConcurrentHashMap<>();

        @Override
        public boolean tryAcquire(String key, int limit, Duration window) {
            long bucket = Instant.now().getEpochSecond() / window.toSeconds();
            long[] state = windows.compute(key, (k, current) ->
                    current == null || current[0] != bucket ? new long[] {bucket, 1} : new long[] {bucket, current[1] + 1});
            return state[1] <= limit;
        }
    }
}
