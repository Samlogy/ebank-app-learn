package com.ebank.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.VaultVersionedKeyValueOperations;
import org.springframework.vault.support.Policy;
import org.springframework.vault.support.VaultMount;
import org.springframework.vault.support.VaultTokenRequest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

/**
 * The Vault part of application.yml against a real Vault: where the backend reads its config and
 * secrets, and what happens when Vault is off or unreachable. Only the configuration is loaded,
 * not the whole application, so no database is needed.
 */
@Testcontainers
class VaultConfigIT {

    private static final String ROOT_TOKEN = "it-root-token";

    @Container
    static final VaultContainer<?> VAULT = new VaultContainer<>("hashicorp/vault:2.2.1").withVaultToken(ROOT_TOKEN);

    @Configuration(proxyBeanMethods = false)
    static class ConfigOnly {}

    /** The same layout as infra/vault: a KV v2 mount "ebank", one policy per component and environment. */
    @BeforeAll
    static void prepareVault() {
        VaultTemplate root = new VaultTemplate(
                VaultEndpoint.from(URI.create(VAULT.getHttpHostAddress())), new TokenAuthentication(ROOT_TOKEN));
        root.opsForSys().mount("ebank", VaultMount.builder().type("kv").options(Map.of("version", "2")).build());
        VaultVersionedKeyValueOperations kv = root.opsForVersionedKeyValue("ebank");
        kv.put("it/backend/config", Map.of(
                "DB_URL", "jdbc:postgresql://db-from-vault:5432/ebank",
                "REDIS_HOST", "redis-from-vault"));
        kv.put("it/backend/secrets", Map.of("DB_PASSWORD", "secret-from-vault"));
        kv.put("other/backend/secrets", Map.of("DB_PASSWORD", "other-env-secret"));
        root.opsForSys().createOrUpdatePolicy("ebank-it-backend", Policy.of(Policy.Rule.builder()
                .path("ebank/data/it/backend/*")
                .capabilities(Policy.BuiltinCapabilities.READ)
                .build()));
        root.opsForToken().create(VaultTokenRequest.builder()
                .id("it-backend-token")
                .withPolicy("ebank-it-backend")
                .build());
    }

    private static ConfigurableApplicationContext start(String... args) {
        SpringApplication app = new SpringApplicationBuilder(ConfigOnly.class)
                .web(WebApplicationType.NONE)
                .build();
        return app.run(args);
    }

    private static String[] vaultArgs(String env, String token) {
        return new String[] {
            "--VAULT_ENABLED=true",
            "--VAULT_ADDR=" + VAULT.getHttpHostAddress(),
            "--VAULT_TOKEN=" + token,
            "--EBANK_ENV=" + env,
        };
    }

    @Test
    void readsConfigAndSecretsOfItsEnvironment() {
        try (ConfigurableApplicationContext context = start(vaultArgs("it", "it-backend-token"))) {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("DB_URL")).isEqualTo("jdbc:postgresql://db-from-vault:5432/ebank");
            assertThat(env.getProperty("REDIS_HOST")).isEqualTo("redis-from-vault");
            assertThat(env.getProperty("DB_PASSWORD")).isEqualTo("secret-from-vault");
            // The profile files' placeholders resolve from Vault.
            assertThat(env.resolvePlaceholders("${DB_URL:default}")).contains("db-from-vault");
        }
    }

    @Test
    void environmentVariablesStillWinOverVault() {
        String[] args = vaultArgs("it", "it-backend-token");
        String[] withOverride = java.util.Arrays.copyOf(args, args.length + 1);
        withOverride[args.length] = "--REDIS_HOST=redis-from-override";
        try (ConfigurableApplicationContext context = start(withOverride)) {
            assertThat(context.getEnvironment().getProperty("REDIS_HOST")).isEqualTo("redis-from-override");
        }
    }

    @Test
    void cannotReadAnotherEnvironment() {
        // The token's policy only covers ebank/it/backend/*: another environment's values stay out.
        try (ConfigurableApplicationContext context = start(vaultArgs("other", "it-backend-token"))) {
            assertThat(context.getEnvironment().getProperty("DB_PASSWORD")).isNull();
        }
    }

    @Test
    void startsWithoutVaultWhenItIsOff() {
        try (ConfigurableApplicationContext context = start("--VAULT_ENABLED=false")) {
            assertThat(context.getEnvironment().getProperty("DB_URL")).isNull();
        }
    }

    @Test
    void failsFastWhenVaultIsRequiredButUnreachable() {
        assertThatThrownBy(() -> start(
                        "--VAULT_ENABLED=true",
                        "--VAULT_FAIL_FAST=true",
                        "--VAULT_ADDR=http://localhost:1",
                        "--VAULT_TOKEN=x",
                        "--EBANK_ENV=it"))
                .isInstanceOf(Exception.class);
    }
}
