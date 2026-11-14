package com.ebank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The admin ledger export: CSV of closed days, identical from one export to the next. */
class LedgerExportIT extends IntegrationTestBase {

    private static final String EXPORT = "/api/v1/ledger/export";
    // A past day of its own: entries of the other tests are booked today.
    private static final LocalDate DAY = LocalDate.of(2001, 1, 15);

    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> export(String roles, String from, String to) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + EXPORT + "?from=" + from + "&to=" + to))
                .header("Authorization", "Bearer someone~" + roles)
                .header("Accept", "text/csv, application/problem+json")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Two accounts, a deposit and a transfer, booked on {@link #DAY}. Returns the transaction ID of the transfer. */
    private String bookOnDay() {
        String alice = newUser();
        String from = createActiveAccount(alice, "EUR");
        String to = createActiveAccount(newUser(), "EUR");
        Response deposit = call("POST", "/api/v1/transactions/deposits", "admin-user", "ADMIN", UUID.randomUUID().toString(),
                Map.of("receiverAccountId", from, "amount", new BigDecimal("100.00"), "currency", "EUR"));
        assertThat(deposit.status()).isEqualTo(201);
        Map<String, Object> body = new HashMap<>(Map.of(
                "senderAccountId", from, "receiverAccountId", to, "amount", new BigDecimal("12.34"), "currency", "EUR"));
        Response transfer = call("POST", "/api/v1/transactions", alice, null, UUID.randomUUID().toString(), body);
        assertThat(transfer.status()).isEqualTo(201);
        // Test only: entries are immutable in the application, here they are moved to a past day.
        jdbc.update("""
                UPDATE ledger_entries SET created_at = ?::timestamptz + (created_at - date_trunc('day', created_at))
                WHERE transfer_id IN (SELECT id FROM transfers WHERE sender_account_id = ? OR receiver_account_id = ?)
                """, DAY.atStartOfDay(ZoneOffset.UTC).toString(), from, from);
        return transfer.body().get("transactionId").asString();
    }

    @Test
    void adminGetsTheEntriesOfClosedDaysAsCsv() throws Exception {
        String transactionId = bookOnDay();

        HttpResponse<String> response = export("ADMIN", DAY.toString(), DAY.toString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/csv"));
        assertThat(response.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"ebank-ledger-2001-01-15-2001-01-15.csv\"");
        List<String> lines = response.body().lines().toList();
        assertThat(lines.getFirst()).isEqualTo("entry_id,transfer_id,transaction_id,account_id,type,amount,currency,booked_at");
        // Double entry: the deposit (debit of the external account, credit) and the transfer.
        assertThat(lines).hasSize(1 + 4);
        assertThat(response.headers().firstValue("X-Ledger-Entry-Count")).hasValue("4");
        List<String[]> rows = lines.stream().skip(1).map(line -> line.split(",", -1)).toList();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row).hasSize(8);
            assertThat(row[6]).isEqualTo("EUR");
            assertThat(row[7]).startsWith("2001-01-15T").endsWith("Z");
        });
        BigDecimal debits = rows.stream().filter(row -> row[4].equals("DEBIT")).map(row -> new BigDecimal(row[5])).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = rows.stream().filter(row -> row[4].equals("CREDIT")).map(row -> new BigDecimal(row[5])).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits);
        List<String[]> transferRows = rows.stream().filter(row -> row[2].equals(transactionId)).toList();
        assertThat(transferRows).extracting(row -> row[4]).containsExactlyInAnyOrder("DEBIT", "CREDIT");
        assertThat(transferRows).extracting(row -> new BigDecimal(row[5])).allSatisfy(amount -> assertThat(amount).isEqualByComparingTo("12.34"));

        // Closed days do not change: the same export gives the same bytes.
        assertThat(export("ADMIN", DAY.toString(), DAY.toString()).body()).isEqualTo(response.body());
    }

    @Test
    void customersCannotExportTheLedger() throws Exception {
        assertThat(export("", DAY.toString(), DAY.toString()).statusCode()).isEqualTo(403);
    }

    @Test
    void onlyClosedDaysAndShortRangesCanBeExported() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        HttpResponse<String> notClosed = export("ADMIN", today.minusDays(1).toString(), today.toString());
        assertThat(notClosed.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(notClosed.body()).path("code").asString()).isEqualTo("DAY_NOT_CLOSED");

        HttpResponse<String> tooLong = export("ADMIN", "2001-01-01", "2001-02-15");
        assertThat(tooLong.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(tooLong.body()).path("code").asString()).isEqualTo("RANGE_TOO_LONG");

        HttpResponse<String> reversed = export("ADMIN", "2001-01-15", "2001-01-14");
        assertThat(reversed.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(reversed.body()).path("code").asString()).isEqualTo("INVALID_RANGE");
    }
}
