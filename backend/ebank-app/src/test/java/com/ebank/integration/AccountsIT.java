package com.ebank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AccountsIT extends IntegrationTestBase {

    private static final String ACCOUNTS = "/api/v1/accounts";

    @Test
    void createsAnAccountForTheCaller() {
        String alice = newUser();

        Response created = as(alice, "POST", ACCOUNTS, Map.of("type", "SAVING", "currency", "EUR"));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("accountId").asString()).matches("EB\\d{22}");
        assertThat(created.body().get("status").asString()).isEqualTo("CREATED");
        assertThat(created.body().get("userId").asString()).isEqualTo(alice);
        assertThat(created.body().get("version").asLong()).isZero();
    }

    @Test
    void requestWithoutAUserIsUnauthorized() {
        Response response = call("GET", ACCOUNTS, null, null, null, null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("UNAUTHORIZED");
        assertThat(response.body().get("status").asInt()).isEqualTo(401);
    }

    @Test
    void userCannotReadOrChangeAnotherUsersAccount() {
        String accountId = createAccount(newUser(), "EUR");
        String mallory = newUser();
        String path = ACCOUNTS + "/" + accountId;

        assertThat(as(mallory, "GET", path, null).status()).isEqualTo(404);
        assertThat(as(mallory, "GET", path + "/history", null).status()).isEqualTo(404);
        assertThat(as(mallory, "PUT", path, Map.of("type", "SAVING")).status()).isEqualTo(404);
        assertThat(as(mallory, "PATCH", path + "/disable", null).status()).isEqualTo(404);
        assertThat(as(mallory, "PATCH", path + "/close", null).status()).isEqualTo(404);
        assertThat(asAdmin("GET", path, null).status()).isEqualTo(200);
    }

    @Test
    void listShowsOnlyTheCallersAccounts() {
        String alice = newUser();
        String first = createAccount(alice, "EUR");
        String second = createAccount(alice, "USD");
        createAccount(newUser(), "EUR");

        JsonNode page = as(alice, "GET", ACCOUNTS, null).body();

        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("accountId").asString()));
        assertThat(ids).containsExactlyInAnyOrder(first, second);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void followsTheStateMachineAndRecordsHistory() {
        String alice = newUser();
        String accountId = createAccount(alice, "EUR");
        String path = ACCOUNTS + "/" + accountId;

        assertThat(as(alice, "PATCH", path + "/activate", null).status()).isEqualTo(403);
        assertThat(asAdmin("PATCH", path + "/activate", null).body().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(as(alice, "PUT", path, Map.of("type", "SAVING")).body().get("type").asString()).isEqualTo("SAVING");

        Response closeWhileActive = as(alice, "PATCH", path + "/close", null);
        assertThat(closeWhileActive.status()).isEqualTo(409);
        assertThat(closeWhileActive.code()).isEqualTo("INVALID_ACCOUNT_STATUS");

        assertThat(as(alice, "PATCH", path + "/disable", null).status()).isEqualTo(200);
        assertThat(as(alice, "PATCH", path + "/close", null).body().get("status").asString()).isEqualTo("CLOSED");
        assertThat(as(alice, "PUT", path, Map.of("type", "CURRENT")).status()).isEqualTo(409);

        // Closed is a soft delete: the account can still be read.
        assertThat(as(alice, "GET", path, null).status()).isEqualTo(200);

        JsonNode items = as(alice, "GET", path + "/history", null).body().get("items");
        List<String> actions = new ArrayList<>();
        items.forEach(item -> actions.add(item.get("action").asString()));
        assertThat(actions).containsExactly("CLOSED", "DISABLED", "TYPE_CHANGED", "ACTIVATED", "CREATED");
        assertThat(items.get(3).get("changedBy").asString()).isEqualTo("admin-user");
        assertThat(items.get(3).get("statusBefore").asString()).isEqualTo("CREATED");
    }

    @Test
    void versionIncreasesWithEachChange() {
        String alice = newUser();
        String path = ACCOUNTS + "/" + createAccount(alice, "EUR");

        assertThat(asAdmin("PATCH", path + "/activate", null).body().get("version").asLong()).isEqualTo(1);
        assertThat(as(alice, "GET", path, null).body().get("version").asLong()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidInputWithProblemDetails() {
        String alice = newUser();

        Response missing = as(alice, "POST", ACCOUNTS, Map.of("currency", "EUR"));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(missing.body().get("violations").get(0).get("field").asString()).isEqualTo("type");

        Response unknownType = as(alice, "POST", ACCOUNTS, Map.of("type", "GOLD", "currency", "EUR"));
        assertThat(unknownType.status()).isEqualTo(400);

        assertThat(as(alice, "POST", ACCOUNTS, Map.of("type", "CURRENT", "currency", "ZZZ")).code())
                .isEqualTo("UNSUPPORTED_CURRENCY");
        assertThat(as(alice, "POST", ACCOUNTS, Map.of("type", "CRYPTO", "currency", "EUR")).code())
                .isEqualTo("CURRENCY_TYPE_MISMATCH");
        assertThat(as(alice, "GET", ACCOUNTS + "?size=1000", null).status()).isEqualTo(400);
    }
}
