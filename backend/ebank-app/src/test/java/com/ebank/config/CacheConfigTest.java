package com.ebank.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.serializer.RedisSerializer;

class CacheConfigTest {

    enum Type { SAVING }

    record CachedAccount(String accountId, Type type, BigDecimal amount, Instant createdAt, List<String> tags) {}

    private static final CachedAccount ACCOUNT = new CachedAccount(
            "FR76-1", Type.SAVING, new BigDecimal("10.50"), Instant.parse("2026-01-01T10:15:30.123456Z"), List.of("a", "b"));

    private final RedisSerializer<Object> serializer = CacheConfig.cacheValueSerializer();

    static Stream<Object> cacheableValues() {
        return Stream.of(
                ACCOUNT,
                List.of(ACCOUNT),
                List.of(ACCOUNT, ACCOUNT, ACCOUNT),
                List.of(),
                Stream.of(ACCOUNT).toList(),
                new ArrayList<>(List.of(ACCOUNT)),
                Set.of(ACCOUNT),
                new BigDecimal("10.50"),
                Type.SAVING,
                UUID.fromString("8708a114-259d-4396-83e2-3644ef023b09"),
                5L,
                "text");
    }

    @ParameterizedTest
    @MethodSource("cacheableValues")
    void roundTripsWithoutChangingTheValueOrItsType(Object value) {
        Object restored = serializer.deserialize(serializer.serialize(value));

        assertThat(restored).isEqualTo(value);
        if (!(value instanceof List<?>) && !(value instanceof Set<?>)) {
            assertThat(restored).hasSameClassAs(value);
        }
    }

    @Test
    void keepsBigDecimalScale() {
        Object restored = serializer.deserialize(serializer.serialize(new BigDecimal("10.50")));

        assertThat(((BigDecimal) restored).scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "java.lang.ProcessBuilder",
        "java.lang.StringBuilder",
        "java.net.URL",
        "java.lang.Thread",
        "javax.script.ScriptEngineManager",
        "com.zaxxer.hikari.HikariConfig",
        "org.postgresql.ds.PGSimpleDataSource",
        "org.springframework.context.support.ClassPathXmlApplicationContext"
    })
    void allowlistRejectsTypesInBothJsonForms(String className) {
        assertDenied("{\"@class\":\"" + className + "\"}");
        assertDenied("[\"" + className + "\",{}]");
    }

    private void assertDenied(String json) {
        assertThatThrownBy(() -> serializer.deserialize(json.getBytes(StandardCharsets.UTF_8)))
                .hasStackTraceContaining("denied resolution");
    }
}
