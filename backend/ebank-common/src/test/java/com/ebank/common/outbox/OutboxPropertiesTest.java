package com.ebank.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxPropertiesTest {

    private final OutboxProperties properties = new OutboxProperties(50, 10, Duration.ofMinutes(15), Duration.ofDays(7));

    @Test
    void backoffDoublesAndIsCapped() {
        assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.backoffAfter(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.backoffAfter(5)).isEqualTo(Duration.ofSeconds(32));
        assertThat(properties.backoffAfter(10)).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.backoffAfter(1000)).isEqualTo(Duration.ofMinutes(15));
    }
}
