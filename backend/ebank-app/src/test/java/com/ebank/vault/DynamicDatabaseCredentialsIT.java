package com.ebank.vault;

import static org.assertj.core.api.Assertions.assertThat;

import com.ebank.EbankApplication;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.Policy;
import org.springframework.vault.support.VaultMount;
import org.springframework.vault.support.VaultTokenRequest;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.vault.VaultContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dynamic PostgreSQL credentials end to end, with the files from infra/vault: database/setup.sql,
 * database/backend-role.json and policies/backend.hcl.
 *
 * <p>The migration path of a real environment: the schema first exists with the static user, then
 * setup.sql hands it over to ebank_app, and the backend starts with a user of its own from Vault.
 * The role's lifetimes are cut to seconds, so the switch to a new user happens during the test.
 */
class DynamicDatabaseCredentialsIT {

    private static final String ROOT_TOKEN = "it-root-token";
    private static final Path INFRA_VAULT = Path.of("..", "..", "infra", "vault");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final Network NETWORK = Network.newNetwork();

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("ebank")
            .withUsername("ebank")
            .withPassword("ebank")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres");

    static final VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:2.2.1")
            .withVaultToken(ROOT_TOKEN)
            .withNetwork(NETWORK);

    static VaultTemplate root;

    @BeforeAll
    static void startContainers() {
        POSTGRES.start();
        VAULT.start();
        root = new VaultTemplate(
                VaultEndpoint.from(URI.create(VAULT.getHttpHostAddress())), new TokenAuthentication(ROOT_TOKEN));
        root.opsForSys().mount("ebank", VaultMount.builder().type("kv").options(Map.of("version", "2")).build());
        root.opsForVersionedKeyValue("ebank").put("it/backend/config", Map.of("DB_URL", POSTGRES.getJdbcUrl()));
        // The static user is still in backend/secrets: the dynamic one must win over it.
        root.opsForVersionedKeyValue("ebank").put("it/backend/secrets", Map.of("DB_USERNAME", "ebank", "DB_PASSWORD", "ebank"));
    }

    @AfterAll
    static void stopContainers() {
        VAULT.stop();
        POSTGRES.stop();
        NETWORK.close();
    }

    @Test
    void backendRunsOnItsOwnDatabaseUserAndSwitchesToANewOneBeforeItExpires() throws Exception {
        // 1. The schema as it is today: migrated by the static user.
        try (ConfigurableApplicationContext app = start(false, null)) {
            assertThat(sessionUser(app)).isEqualTo("ebank");
        }

        // 2. enable-dynamic-db.sh, minus kubectl.
        prepareDatabase();
        String token = prepareVault();
        assertThat(owners()).containsOnly("ebank_app");

        // 3. The backend with dynamic credentials.
        String lastUser;
        try (ConfigurableApplicationContext app = start(true, token)) {
            JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
            String firstUser = sessionUser(app);
            assertThat(firstUser).startsWith("v-ebank-it-backend-").isNotEqualTo("ebank");
            // Logged in as its own user, acting as ebank_app: it reads and writes the existing tables.
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("ebank_app");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class)).isPositive();
            jdbc.update("CREATE TABLE it_created_by_dynamic_user (id int)");
            assertThat(ownerOf("it_created_by_dynamic_user")).isEqualTo("ebank_app");

            // Queries keep working while the lease reaches max_ttl and the pool moves to a new user.
            List<Exception> failures = new ArrayList<>();
            AtomicReference<String> current = new AtomicReference<>(firstUser);
            waitUntil(Duration.ofSeconds(60), () -> {
                try {
                    current.set(jdbc.queryForObject("SELECT session_user", String.class));
                } catch (RuntimeException ex) {
                    failures.add(ex);
                }
                return !current.get().equals(firstUser) && !roleExists(firstUser);
            });
            assertThat(failures).isEmpty();
            assertThat(current.get()).startsWith("v-ebank-it-backend-").isNotEqualTo(firstUser);
            // Vault dropped the first user when its lease ended.
            assertThat(roleExists(firstUser)).isFalse();

            MeterRegistry meters = app.getBean(MeterRegistry.class);
            assertThat(meters.get("ebank.vault.database.credentials.rotations").tag("outcome", "success").counter().count())
                    .isGreaterThanOrEqualTo(1);
            assertThat(meters.get("ebank.vault.database.credentials.expiry").gauge().value()).isPositive();
            lastUser = sessionUser(app);
        }
        // On shutdown the backend revokes its lease (revoke-prefix, allowed by its policy).
        String revoked = lastUser;
        waitUntil(Duration.ofSeconds(10), () -> !roleExists(revoked));
        assertThat(roleExists(revoked)).isFalse();
    }

    private static void prepareDatabase() throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(INFRA_VAULT.resolve("database/setup.sql")), "/tmp/setup.sql");
        ExecResult result = POSTGRES.execInContainer("sh", "-c",
                "VAULT_ADMIN_PASSWORD=it-admin-password psql -v ON_ERROR_STOP=1 -q -U ebank -d ebank -f /tmp/setup.sql");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    /** The Vault half of enable-dynamic-db.sh. Returns a token with the backend's real policy. */
    private static String prepareVault() throws Exception {
        root.opsForSys().mount("database", VaultMount.create("database"));
        root.write("database/config/ebank-it", Map.of(
                "plugin_name", "postgresql-database-plugin",
                "connection_url", "postgresql://{{username}}:{{password}}@postgres:5432/ebank?sslmode=disable",
                "username", "vault_admin",
                "password", "it-admin-password",
                "password_authentication", "scram-sha-256",
                "allowed_roles", "ebank-it-backend",
                "username_template", "v-{{.RoleName | truncate 24}}-{{random 12 | lowercase}}-{{unix_time}}"));
        String roleJson = Files.readString(INFRA_VAULT.resolve("database/backend-role.json")).replace("__ENV__", "it");
        @SuppressWarnings("unchecked")
        Map<String, Object> role = new java.util.HashMap<>(JSON.readValue(roleJson, Map.class));
        // Seconds instead of hours, so the lease reaches its end during the test.
        role.put("default_ttl", "10s");
        role.put("max_ttl", "20s");
        root.write("database/roles/ebank-it-backend", role);
        root.write("database/rotate-root/ebank-it", Map.of());

        String policy = Files.readString(INFRA_VAULT.resolve("policies/backend.hcl")).replace("__ENV__", "it");
        root.write("sys/policies/acl/ebank-it-backend", Map.of("policy", policy));
        return root.opsForToken()
                .create(VaultTokenRequest.builder().withPolicy("ebank-it-backend").renewable().ttl(Duration.ofHours(1)).build())
                .getToken()
                .getToken();
    }

    private static ConfigurableApplicationContext start(boolean dynamic, String token) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=dev",
                "--server.port=0",
                "--DB_URL=" + POSTGRES.getJdbcUrl(),
                "--spring.cache.type=none",
                "--management.health.redis.enabled=false",
                "--ebank.analytics.enabled=false",
                "--ebank.outbox.relay-enabled=false",
                "--ebank.notifications.keycloak.client-secret=",
                "--ebank.chatbot.assistant.api-key=",
                "--ebank.chatbot.rate-limit.store=memory"));
        if (dynamic) {
            args.addAll(List.of(
                    "--VAULT_ENABLED=true",
                    "--VAULT_FAIL_FAST=true",
                    "--VAULT_ADDR=" + VAULT.getHttpHostAddress(),
                    "--VAULT_TOKEN=" + token,
                    "--EBANK_ENV=it",
                    "--VAULT_DATABASE_ENABLED=true",
                    // Renew early and often, so the 20 s max_ttl is handled like a 24 h one.
                    "--spring.cloud.vault.config.lifecycle.min-renewal=4s",
                    "--spring.cloud.vault.config.lifecycle.expiry-threshold=8s"));
        } else {
            args.addAll(List.of("--DB_USERNAME=ebank", "--DB_PASSWORD=ebank"));
        }
        return new SpringApplicationBuilder(EbankApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(args.toArray(String[]::new));
    }

    private static String sessionUser(ConfigurableApplicationContext app) {
        return app.getBean(JdbcTemplate.class).queryForObject("SELECT session_user", String.class);
    }

    /** Through the superuser, outside the application's pool. */
    private static JdbcTemplate admin() {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "ebank", "ebank");
        return new JdbcTemplate(dataSource);
    }

    private static boolean roleExists(String name) {
        return admin().queryForObject("SELECT count(*) FROM pg_roles WHERE rolname = ?", Integer.class, name) > 0;
    }

    private static String ownerOf(String table) {
        return admin().queryForObject(
                "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = ? AND relnamespace = 'public'::regnamespace",
                String.class, table);
    }

    private static List<String> owners() {
        return admin().queryForList(
                "SELECT DISTINCT pg_get_userbyid(relowner) FROM pg_class WHERE relnamespace = 'public'::regnamespace",
                String.class);
    }

    private static void waitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        Instant end = Instant.now().plus(timeout);
        while (Instant.now().isBefore(end)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(250);
        }
    }
}
