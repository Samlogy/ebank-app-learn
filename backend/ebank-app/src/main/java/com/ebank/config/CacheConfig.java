package com.ebank.config;

import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Stores cache values in Redis as JSON instead of JDK serialization. Cached classes are
 * limited to an allowlist, so tampered Redis data cannot instantiate arbitrary types.
 *
 * <p>Cache records, lists and sets. Avoid {@code Map.of(...)} as a cached value: it comes
 * back with an extra {@code @class} entry. Wrap it in a record or use a {@code HashMap}.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig {

    /** Defining this bean disables spring.cache.redis.*, so those values are applied here. */
    @Bean
    RedisCacheConfiguration redisCacheConfiguration(CacheProperties properties) {
        CacheProperties.Redis redis = properties.getRedis();
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(SerializationPair.fromSerializer(cacheValueSerializer()))
                .prefixCacheNameWith(redis.getKeyPrefix())
                .entryTtl(redis.getTimeToLive());
        return redis.isCacheNullValues() ? config : config.disableCachingNullValues();
    }

    static RedisSerializer<Object> cacheValueSerializer() {
        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.ebank.")
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.math.")
                        .allowIfSubType("java.time.")
                        .allowIfSubType(Long.class)
                        .build())
                // By default, final java.* values such as List.of(...) are written without type info.
                .writer((mapper, value) -> mapper.writerFor(Object.class).writeValueAsBytes(value))
                // By default, a root @class becomes the target type, which skips the allowlist
                // for final java.* classes. Reading as Object always applies it.
                .reader((mapper, source, type) -> mapper.readValue(source, Object.class))
                .build();
    }
}
