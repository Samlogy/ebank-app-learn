package com.ebank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the API contract the application really serves to target/openapi/ebank-api.json.
 * infra/ci/security/scan.sh api compares it with the committed backend/openapi/ebank-api.json
 * (must be up to date) and with master's (must stay backward compatible).
 *
 * After an intended API change: cp ebank-app/target/openapi/ebank-api.json openapi/ (from backend/).
 */
class OpenApiSpecIT extends IntegrationTestBase {

    private static final JsonMapper PRETTY = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Test
    void writesTheServedContract() throws Exception {
        Response response = call("GET", "/v3/api-docs", null, null, null, null);
        assertThat(response.status()).isEqualTo(200);

        ObjectNode spec = (ObjectNode) response.body();
        // The test server's random port is not part of the contract.
        spec.remove("servers");
        assertThat(spec.path("paths").has("/api/v1/transactions")).isTrue();
        assertThat(spec.path("paths").has("/api/v1/chat/conversations")).isTrue();

        Path out = Path.of("target", "openapi", "ebank-api.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, PRETTY.writeValueAsString(PRETTY.treeToValue(spec, Object.class)) + "\n");
    }
}
