package com.ebank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * The latency SLO counts requests under 1 s from the bucket le="1.0"
 * (infra/observability/prometheus/rules/ebank-slo.yml). Micrometer's default buckets have no
 * exact 1 s, so application.yml adds it: this test keeps the two in step.
 */
class SloMetricsIT extends IntegrationTestBase {

    @Test
    void httpRequestHistogramHasTheSloBuckets() throws Exception {
        as(newUser(), "GET", "/api/v1/accounts", null);

        HttpResponse<String> metrics = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body().lines().filter(line -> line.startsWith("http_server_requests_seconds_bucket{")))
                .anyMatch(line -> line.contains("le=\"1.0\"") && line.contains("uri=\"/api/v1/accounts\""))
                .anyMatch(line -> line.contains("le=\"0.5\""));
    }
}
