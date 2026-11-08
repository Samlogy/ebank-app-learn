package com.ebank.auth.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counts 401 and 403 responses, exported as {@code ebank_security_rejections_total}. */
@Component
class SecurityRejections {

    private final Counter unauthorized;
    private final Counter forbidden;

    SecurityRejections(MeterRegistry registry) {
        this.unauthorized = counter(registry, "401");
        this.forbidden = counter(registry, "403");
    }

    void countUnauthorized() {
        unauthorized.increment();
    }

    void countForbidden() {
        forbidden.increment();
    }

    private static Counter counter(MeterRegistry registry, String status) {
        return Counter.builder("ebank.security.rejections")
                .description("Requests rejected by authentication or authorization")
                .tag("status", status)
                .register(registry);
    }
}
