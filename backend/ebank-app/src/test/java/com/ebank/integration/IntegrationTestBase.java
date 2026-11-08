package com.ebank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/** Runs the real application on a real PostgreSQL and calls it over HTTP. Redis is not needed. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.cache.type=none", "management.health.redis.enabled=false"})
@ActiveProfiles("dev")
@Import(IntegrationTestBase.StubTokens.class)
abstract class IntegrationTestBase {

    /** Reads "user~ROLES" tokens instead of calling Keycloak. Authorization rules still run for real. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubTokens {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                String[] parts = token.split("~", -1);
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject(parts[0])
                        .claim("realm_access", Map.of("roles", parts[1].isEmpty() ? List.of() : List.of(parts[1])))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
            };
        }
    }

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static {
        POSTGRES.start();
    }

    /** Numbers are read as BigDecimal, so tests see the exact decimals the API sent. */
    static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    record Response(int status, JsonNode body) {
        String code() {
            return body.path("code").asString();
        }
    }

    Response call(String method, String path, String userId, String roles, String idempotencyKey, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (userId != null) {
            request.header("Authorization", "Bearer " + userId + "~" + (roles == null ? "" : roles));
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            return new Response(response.statusCode(), text.isEmpty() ? JSON.nullNode() : JSON.readTree(text));
        } catch (java.io.IOException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    Response as(String userId, String method, String path, Object body) {
        return call(method, path, userId, null, null, body);
    }

    Response asAdmin(String method, String path, Object body) {
        return call(method, path, "admin-user", "ADMIN", null, body);
    }

    static String newUser() {
        return "user-" + UUID.randomUUID();
    }

    /** Creates an account in CREATED status and returns its business ID. */
    String createAccount(String userId, String currency) {
        Response created = as(userId, "POST", "/api/v1/accounts", java.util.Map.of("type", "CURRENT", "currency", currency));
        assertThat(created.status()).isEqualTo(201);
        return created.body().get("accountId").asString();
    }

    String createActiveAccount(String userId, String currency) {
        String accountId = createAccount(userId, currency);
        assertThat(asAdmin("PATCH", "/api/v1/accounts/" + accountId + "/activate", null).status()).isEqualTo(200);
        return accountId;
    }
}
