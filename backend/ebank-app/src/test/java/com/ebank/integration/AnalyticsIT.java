package com.ebank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;

/** The whole path: transfer → outbox → Elasticsearch → analytics API, on a real Elasticsearch. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsIT extends IntegrationTestBase {

    private static final String ANALYTICS = "/api/v1/analytics";
    private static final Duration WAIT = Duration.ofSeconds(20);

    @SuppressWarnings("resource")
    static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>("docker.elastic.co/elasticsearch/elasticsearch:9.4.8")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/_cluster/health?wait_for_status=yellow").forPort(9200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        ELASTICSEARCH.start();
    }

    @DynamicPropertySource
    static void analytics(DynamicPropertyRegistry registry) {
        registry.add("ebank.analytics.enabled", () -> "true");
        registry.add("management.health.elasticsearch.enabled", () -> "true");
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
    }

    @Autowired JdbcTemplate jdbc;

    private String alice;
    private String bob;
    private String aliceMain;
    private String aliceSavings;
    private String bobAccount;
    private String sentTransactionId;

    private void deposit(String accountId, String amount) {
        Response response = call("POST", "/api/v1/transactions/deposits", "admin-user", "ADMIN", UUID.randomUUID().toString(),
                Map.of("receiverAccountId", accountId, "amount", new BigDecimal(amount), "currency", "EUR"));
        assertThat(response.status()).isEqualTo(201);
    }

    private Response transfer(String userId, String from, String to, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("senderAccountId", from);
        body.put("receiverAccountId", to);
        body.put("amount", new BigDecimal(amount));
        body.put("currency", "EUR");
        body.put("reference", "rent");
        return call("POST", "/api/v1/transactions", userId, null, UUID.randomUUID().toString(), body);
    }

    /** Analytics are eventually consistent: outbox delay plus Elasticsearch's refresh interval. */
    private JsonNode await(String userId, String path, Predicate<JsonNode> ready) {
        Instant deadline = Instant.now().plus(WAIT);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            Response response = as(userId, "GET", path, null);
            assertThat(response.status()).as(path).isEqualTo(200);
            last = response.body();
            if (ready.test(last)) {
                return last;
            }
            sleep(200);
        }
        throw new AssertionError("Not ready within " + WAIT + ": " + path + " -> " + last);
    }

    private static JsonNode eur(JsonNode summary) {
        for (JsonNode currency : summary.get("currencies")) {
            if ("EUR".equals(currency.get("currency").asString())) {
                return currency;
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.decimalValue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** One scenario shared by the tests: a deposit, a transfer, an internal move and a refused transfer. */
    @BeforeAll
    void bookTransactions() {
        alice = newUser();
        bob = newUser();
        aliceMain = createActiveAccount(alice, "EUR");
        aliceSavings = createActiveAccount(alice, "EUR");
        bobAccount = createActiveAccount(bob, "EUR");
        deposit(aliceMain, "100");
        Response sent = transfer(alice, aliceMain, bobAccount, "30.50");
        assertThat(sent.status()).isEqualTo(201);
        sentTransactionId = sent.body().get("transactionId").asString();
        assertThat(transfer(alice, aliceMain, aliceSavings, "10").status()).isEqualTo(201);
        assertThat(transfer(alice, aliceMain, bobAccount, "1000").status()).isEqualTo(422);

        await(alice, ANALYTICS + "/summary", s -> {
            JsonNode eur = eur(s);
            return eur != null && eur.get("cancelledCount").asLong() == 1
                    && eur.get("outgoing").get("count").asLong() == 1 && eur.get("internal").get("count").asLong() == 1
                    && eur.get("incoming").get("count").asLong() == 1;
        });
    }

    @Test
    void summaryGivesExactTotalsFromTheUsersPointOfView() {
        JsonNode aliceEur = eur(as(alice, "GET", ANALYTICS + "/summary", null).body());
        assertThat(decimal(aliceEur.get("incoming").get("total"))).isEqualTo(new BigDecimal("100.00"));
        assertThat(decimal(aliceEur.get("outgoing").get("total"))).isEqualTo(new BigDecimal("30.50"));
        assertThat(decimal(aliceEur.get("internal").get("total"))).isEqualTo(new BigDecimal("10.00"));
        assertThat(decimal(aliceEur.get("net"))).isEqualTo(new BigDecimal("69.50"));

        JsonNode bobEur = eur(await(bob, ANALYTICS + "/summary", s -> eur(s) != null));
        assertThat(decimal(bobEur.get("incoming").get("total"))).isEqualTo(new BigDecimal("30.50"));
        assertThat(bobEur.get("outgoing").get("count").asLong()).isZero();
        assertThat(bobEur.get("cancelledCount").asLong()).as("bob never sees alice's refused transfer").isZero();
    }

    @Test
    void accountFilterCountsInternalMovesAsIncomingOrOutgoing() {
        JsonNode savings = eur(as(alice, "GET", ANALYTICS + "/summary?accountId=" + aliceSavings, null).body());
        assertThat(decimal(savings.get("incoming").get("total"))).isEqualTo(new BigDecimal("10.00"));
        assertThat(savings.get("outgoing").get("count").asLong()).isZero();

        JsonNode main = eur(as(alice, "GET", ANALYTICS + "/summary?accountId=" + aliceMain, null).body());
        assertThat(decimal(main.get("outgoing").get("total"))).isEqualTo(new BigDecimal("40.50"));
    }

    @Test
    void someoneElsesAccountMatchesNothing() {
        JsonNode summary = as(bob, "GET", ANALYTICS + "/summary?accountId=" + aliceMain, null).body();
        assertThat(summary.get("currencies")).isEmpty();
        assertThat(as(newUser(), "GET", ANALYTICS + "/summary", null).body().get("currencies")).isEmpty();
    }

    @Test
    void timelineHasOneBucketPerDayIncludingEmptyOnes() {
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        String weekAgo = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(6).toString();

        JsonNode timeline = as(alice, "GET", ANALYTICS + "/timeline?currency=EUR&interval=DAY&from=" + weekAgo + "&to=" + today, null).body();

        assertThat(timeline.get("points")).hasSize(7);
        JsonNode last = timeline.get("points").get(6);
        assertThat(last.get("start").asString()).isEqualTo(today);
        assertThat(decimal(last.get("incoming"))).isEqualTo(new BigDecimal("100.00"));
        assertThat(decimal(last.get("outgoing"))).isEqualTo(new BigDecimal("30.50"));
        assertThat(decimal(timeline.get("points").get(0).get("net"))).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void counterpartiesInBothDirections() {
        JsonNode outgoing = as(alice, "GET", ANALYTICS + "/counterparties?currency=EUR&direction=OUTGOING", null).body();
        assertThat(outgoing.get("items")).hasSize(1);
        assertThat(outgoing.get("items").get(0).get("accountId").asString()).isEqualTo(bobAccount);
        assertThat(decimal(outgoing.get("items").get(0).get("total"))).isEqualTo(new BigDecimal("30.50"));

        JsonNode incoming = as(alice, "GET", ANALYTICS + "/counterparties?currency=EUR&direction=INCOMING", null).body();
        assertThat(incoming.get("items").get(0).get("accountId").asString()).isEqualTo("EXTERNAL-EUR");
    }

    @Test
    void overviewIsForAdminsOnly() {
        assertThat(as(alice, "GET", ANALYTICS + "/overview", null).status()).isEqualTo(403);

        Response overview = asAdmin("GET", ANALYTICS + "/overview", null);
        assertThat(overview.status()).isEqualTo(200);
        JsonNode eur = eur(overview.body());
        assertThat(decimal(eur.get("transfers").get("total"))).isGreaterThanOrEqualTo(new BigDecimal("40.50"));
        assertThat(eur.get("deposits").get("count").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(eur.get("cancelled").get("count").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(eur.get("activeUsers").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void invalidRequestsAreRejected() {
        assertThat(as(alice, "GET", ANALYTICS + "/summary?from=2026-02-01&to=2026-01-01", null).code()).isEqualTo("INVALID_RANGE");
        assertThat(as(alice, "GET", ANALYTICS + "/summary?from=2024-01-01&to=2026-01-01", null).code()).isEqualTo("RANGE_TOO_LONG");
        assertThat(as(alice, "GET", ANALYTICS + "/timeline?currency=XXX", null).code()).isEqualTo("UNSUPPORTED_CURRENCY");
        assertThat(as(alice, "GET", ANALYTICS + "/timeline?currency=EUR&interval=YEAR", null).status()).isEqualTo(400);
        assertThat(as(alice, "GET", ANALYTICS + "/timeline", null).status()).isEqualTo(400);
        assertThat(call("GET", ANALYTICS + "/summary", null, null, null, null).status()).isEqualTo(401);
    }

    @Test
    void redeliveredEventIsNotCountedTwice() {
        // Replay the analytics part of the transfer's event, as a crash between indexing and the commit would.
        String eventId = jdbc.queryForObject(
                "SELECT id::text FROM outbox_event WHERE aggregate_id = ? AND event_type = 'transactions.TransactionCompleted'",
                String.class, sentTransactionId);
        jdbc.update("DELETE FROM outbox_event_handled WHERE event_id = ?::uuid AND handler LIKE 'com.ebank.analytics.%'", eventId);
        jdbc.update("UPDATE outbox_event SET status = 'PENDING', next_attempt_at = now() WHERE id = ?::uuid", eventId);

        Instant deadline = Instant.now().plus(WAIT);
        while (!"PUBLISHED".equals(jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?::uuid", String.class, eventId))
                && Instant.now().isBefore(deadline)) {
            sleep(100);
        }
        sleep(1500);    // past Elasticsearch's 1 s refresh

        JsonNode bobEur = eur(as(bob, "GET", ANALYTICS + "/summary", null).body());
        assertThat(decimal(bobEur.get("incoming").get("total"))).isEqualTo(new BigDecimal("30.50"));
        assertThat(bobEur.get("incoming").get("count").asLong()).isEqualTo(1);
        // Notifications handled it the first time, so the replay did not notify bob again.
        assertThat(as(bob, "GET", "/api/v1/notifications", null).body().get("totalElements").asLong()).isEqualTo(1);
    }
}
