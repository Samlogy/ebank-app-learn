package com.ebank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real token flow: the versioned realm (applied by keycloak-config-cli) in a real Keycloak, real signed tokens, and the
 * backend's own JwtDecoder (issuer, signature from the JWKS, audience ebank-api, realm roles).
 * The other ITs replace the decoder with a stub, so this is the only test of that wiring.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.cache.type=none",
            "management.health.redis.enabled=false",
            "management.health.mail.enabled=false",
            "management.health.elasticsearch.enabled=false",
            "ebank.analytics.enabled=false",
            "ebank.notifications.keycloak.client-secret=",
            "ebank.chatbot.assistant.api-key=",
            "ebank.chatbot.rate-limit.store=memory"
        })
@ActiveProfiles("dev")
class KeycloakIT {

    private static final String PASSWORD = "It-Password-1234";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static final Network NETWORK = Network.newNetwork();

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("keycloak/keycloak:26.8.2")
            .withCommand("start-dev")
            .withNetwork(NETWORK)
            .withNetworkAliases("keycloak")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));

    /** The realm is applied the way compose and the Helm chart do it, $(env:...) placeholders included. */
    static final GenericContainer<?> REALM = new GenericContainer<>("adorsys/keycloak-config-cli:6.5.1-26.5.5")
            .withNetwork(NETWORK)
            .withEnv(Map.of(
                    "KEYCLOAK_URL", "http://keycloak:8080",
                    "KEYCLOAK_USER", "admin",
                    "KEYCLOAK_PASSWORD", "admin",
                    "IMPORT_FILES_LOCATIONS", "/config/ebank-realm.json",
                    "IMPORT_VARSUBSTITUTION_ENABLED", "true",
                    "EBANK_APP_URL", "http://localhost:4200",
                    "KEYCLOAK_NOTIFICATIONS_CLIENT_SECRET", "it-notifications-secret",
                    "KEYCLOAK_SMTP_HOST", "localhost",
                    "KEYCLOAK_SMTP_PORT", "25",
                    "KEYCLOAK_SMTP_FROM", "noreply@ebank.local"))
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../infra/keycloak/realms/ebank-realm.json"), "/config/ebank-realm.json")
            .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(3)));

    static {
        POSTGRES.start();
        KEYCLOAK.start();
        REALM.start();    // returns once keycloak-config-cli has exited with 0
        createClient("it-cli", true);
        createClient("it-no-audience", false);
    }

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> realmUrl());
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> realmUrl() + "/protocol/openid-connect/certs");
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void aRealTokenIsAcceptedAndTheUserIsItsSubject() {
        String userId = createUser(null);
        String token = token("it-cli", userId);

        HttpResponse<String> created = api("POST", "/api/v1/accounts", token, "{\"type\":\"CURRENT\",\"currency\":\"EUR\"}");

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(read(created).get("userId").asString()).isEqualTo(userId);
        assertThat(api("GET", "/api/v1/accounts", token, null).statusCode()).isEqualTo(200);
    }

    @Test
    void realmRolesDecideWhatACallerMayDo() {
        String customer = token("it-cli", createUser(null));
        String admin = token("it-cli", createUser("ADMIN"));
        String accountId = read(api("POST", "/api/v1/accounts", customer, "{\"type\":\"CURRENT\",\"currency\":\"EUR\"}"))
                .get("accountId").asString();

        // New users get CUSTOMER through the realm's default roles; activating needs ADMIN.
        assertThat(api("PATCH", "/api/v1/accounts/" + accountId + "/activate", customer, null).statusCode()).isEqualTo(403);
        assertThat(api("PATCH", "/api/v1/accounts/" + accountId + "/activate", admin, null).statusCode()).isEqualTo(200);
    }

    @Test
    void aTokenForAnotherAudienceIsRejected() {
        String token = token("it-no-audience", createUser(null));

        assertThat(api("GET", "/api/v1/accounts", token, null).statusCode()).isEqualTo(401);
    }

    @Test
    void aTamperedTokenIsRejected() {
        String token = token("it-cli", createUser(null));
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + new StringBuilder(parts[2]).reverse();

        assertThat(api("GET", "/api/v1/accounts", tampered, null).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------------------------ helpers

    private static String baseUrl() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
    }

    private static String realmUrl() {
        return baseUrl() + "/realms/ebank";
    }

    private static String adminToken() {
        return read(form(baseUrl() + "/realms/master/protocol/openid-connect/token", Map.of(
                "grant_type", "password", "client_id", "admin-cli", "username", "admin", "password", "admin")))
                .get("access_token").asString();
    }

    /** A public client with the password grant; with the audience mapper, its tokens are for ebank-api. */
    private static void createClient(String clientId, boolean ebankApiAudience) {
        Map<String, Object> client = new java.util.HashMap<>(Map.of(
                "clientId", clientId, "enabled", true, "publicClient", true,
                "standardFlowEnabled", false, "directAccessGrantsEnabled", true));
        if (ebankApiAudience) {
            client.put("protocolMappers", List.of(Map.of(
                    "name", "audience-ebank-api", "protocol", "openid-connect", "protocolMapper", "oidc-audience-mapper",
                    "config", Map.of("included.client.audience", "ebank-api", "access.token.claim", "true"))));
        }
        HttpResponse<String> response = admin("POST", "/clients", client);
        assertThat(response.statusCode()).isEqualTo(201);
    }

    /** A verified user with a fixed password, and the given realm role on top of the defaults. */
    private static String createUser(String role) {
        String username = "it-" + UUID.randomUUID() + "@ebank.local";
        HttpResponse<String> created = admin("POST", "/users", Map.of(
                "username", username, "email", username, "emailVerified", true, "enabled", true,
                "firstName", "IT", "lastName", "User",
                "credentials", List.of(Map.of("type", "password", "value", PASSWORD, "temporary", false))));
        assertThat(created.statusCode()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();
        String id = location.substring(location.lastIndexOf('/') + 1);
        if (role != null) {
            JsonNode representation = read(admin("GET", "/roles/" + role, null));
            assertThat(admin("POST", "/users/" + id + "/role-mappings/realm", List.of(representation)).statusCode()).isEqualTo(204);
        }
        USERNAMES.put(id, username);
        return id;
    }

    private static final Map<String, String> USERNAMES = new java.util.concurrent.ConcurrentHashMap<>();

    private static String token(String clientId, String userId) {
        return read(form(realmUrl() + "/protocol/openid-connect/token", Map.of(
                "grant_type", "password", "client_id", clientId, "username", USERNAMES.get(userId), "password", PASSWORD)))
                .get("access_token").asString();
    }

    private static HttpResponse<String> admin(String method, String path, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + "/admin/realms/ebank" + path))
                .header("Authorization", "Bearer " + adminToken())
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return send(request.build());
    }

    private static HttpResponse<String> form(String url, Map<String, String> fields) {
        String body = fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b).orElse("");
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private HttpResponse<String> api(String method, String path, String token, String body) {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JsonNode read(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return JSON.readTree(response.body());
    }
}
