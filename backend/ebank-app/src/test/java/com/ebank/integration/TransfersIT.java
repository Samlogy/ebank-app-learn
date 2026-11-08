package com.ebank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

class TransfersIT extends IntegrationTestBase {

    private static final String TRANSACTIONS = "/api/v1/transactions";

    @Autowired JdbcTemplate jdbc;

    private void deposit(String accountId, String amount) {
        Response response = call("POST", TRANSACTIONS + "/deposits", "admin-user", "ADMIN", UUID.randomUUID().toString(),
                Map.of("receiverAccountId", accountId, "amount", new BigDecimal(amount), "currency", "EUR"));
        assertThat(response.status()).isEqualTo(201);
    }

    private Response transfer(String userId, String key, String from, String to, String amount) {
        return transfer(userId, key, from, to, amount, "EUR", "test");
    }

    private Response transfer(String userId, String key, String from, String to, String amount, String currency, String reference) {
        Map<String, Object> body = new HashMap<>();
        body.put("senderAccountId", from);
        body.put("receiverAccountId", to);
        body.put("amount", new BigDecimal(amount));
        body.put("currency", currency);
        body.put("reference", reference);
        return call("POST", TRANSACTIONS, userId, null, key, body);
    }

    private BigDecimal balance(String userId, String accountId) {
        Response response = as(userId, "GET", "/api/v1/accounts/" + accountId + "/balance", null);
        assertThat(response.status()).isEqualTo(200);
        return response.body().get("amount").decimalValue();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void transferMovesMoneyAndWritesTwoLedgerEntries() {
        String alice = newUser();
        String bob = newUser();
        String from = createActiveAccount(alice, "EUR");
        String to = createActiveAccount(bob, "EUR");
        deposit(from, "100.00");

        Response response = transfer(alice, key(), from, to, "30.50");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().get("status").asString()).isEqualTo("DONE");
        assertThat(response.body().get("transactionId").asString()).startsWith("TXN");
        assertThat(response.body().get("amount").decimalValue()).isEqualTo(new BigDecimal("30.50"));
        assertThat(balance(alice, from)).isEqualTo(new BigDecimal("69.50"));
        assertThat(balance(bob, to)).isEqualTo(new BigDecimal("30.50"));
        assertLedgerIsBalanced();
    }

    @Test
    void retryWithTheSameKeyDoesNotMoveMoneyTwice() {
        String alice = newUser();
        String from = createActiveAccount(alice, "EUR");
        String to = createActiveAccount(newUser(), "EUR");
        deposit(from, "100");
        String key = key();

        Response first = transfer(alice, key, from, to, "40");
        Response retry = transfer(alice, key, from, to, "40.00");

        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().get("transactionId").asString()).isEqualTo(first.body().get("transactionId").asString());
        assertThat(balance(alice, from)).isEqualTo(new BigDecimal("60.00"));
    }

    @Test
    void sameKeyWithADifferentRequestIsRejected() {
        String alice = newUser();
        String from = createActiveAccount(alice, "EUR");
        String to = createActiveAccount(newUser(), "EUR");
        deposit(from, "100");
        String key = key();
        transfer(alice, key, from, to, "40");

        Response response = transfer(alice, key, from, to, "41");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(alice, from)).isEqualTo(new BigDecimal("60.00"));
    }

    @Test
    void idempotencyKeysAreSeparatePerUser() {
        String alice = newUser();
        String bob = newUser();
        String aliceAccount = createActiveAccount(alice, "EUR");
        String bobAccount = createActiveAccount(bob, "EUR");
        deposit(aliceAccount, "10");
        deposit(bobAccount, "10");
        String shared = key();

        assertThat(transfer(alice, shared, aliceAccount, bobAccount, "1").status()).isEqualTo(201);
        assertThat(transfer(bob, shared, bobAccount, aliceAccount, "1").status()).isEqualTo(201);
    }

    @Test
    void insufficientFundsIsRecordedAsCancelledAndHiddenFromTheReceiver() {
        String alice = newUser();
        String bob = newUser();
        String from = createActiveAccount(alice, "EUR");
        String to = createActiveAccount(bob, "EUR");
        deposit(from, "10");
        String key = key();

        Response response = transfer(alice, key, from, to, "10.01");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(alice, from)).isEqualTo(new BigDecimal("10.00"));

        JsonNode senderView = as(alice, "GET", TRANSACTIONS + "?sender=" + from + "&receiver=" + to, null).body();
        assertThat(senderView.get("totalElements").asLong()).isEqualTo(1);
        assertThat(senderView.get("items").get(0).get("status").asString()).isEqualTo("CANCELLED");
        String transactionId = senderView.get("items").get(0).get("transactionId").asString();

        assertThat(as(bob, "GET", TRANSACTIONS + "?accountId=" + to + "&sender=" + from, null).body().get("totalElements").asLong()).isZero();
        assertThat(as(bob, "GET", TRANSACTIONS + "/" + transactionId, null).status()).isEqualTo(404);

        // A retry gives the same answer, even though funds have arrived since.
        deposit(from, "100");
        Response retry = transfer(alice, key, from, to, "10.01");
        assertThat(retry.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(alice, from)).isEqualTo(new BigDecimal("110.00"));
    }

    @Test
    void cannotSpendFromSomeoneElsesAccount() {
        String victim = newUser();
        String mallory = newUser();
        String from = createActiveAccount(victim, "EUR");
        String to = createActiveAccount(mallory, "EUR");
        deposit(from, "100");

        assertThat(transfer(mallory, key(), from, to, "1").status()).isEqualTo(404);
        Response admin = call("POST", TRANSACTIONS, "admin-user", "ADMIN", key(), Map.of(
                "senderAccountId", from, "receiverAccountId", to, "amount", BigDecimal.ONE, "currency", "EUR"));
        assertThat(admin.status()).isEqualTo(404);
        assertThat(balance(victim, from)).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void rejectsInvalidTransfers() {
        String alice = newUser();
        String from = createActiveAccount(alice, "EUR");
        String to = createActiveAccount(newUser(), "EUR");
        deposit(from, "100");

        assertThat(transfer(alice, key(), from, to, "0").code()).isEqualTo("INVALID_AMOUNT");
        assertThat(transfer(alice, key(), from, to, "-1").code()).isEqualTo("INVALID_AMOUNT");
        assertThat(transfer(alice, key(), from, to, "1.001").code()).isEqualTo("INVALID_AMOUNT");
        assertThat(transfer(alice, key(), from, to, "1E+100").code()).isEqualTo("INVALID_AMOUNT");
        assertThat(transfer(alice, key(), from, from, "1").code()).isEqualTo("SAME_ACCOUNT");
        assertThat(transfer(alice, key(), from, to, "1", "USD", "x").code()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(transfer(alice, null, from, to, "1").status()).isEqualTo(400);

        String longReference = "x".repeat(141);
        assertThat(transfer(alice, key(), from, to, "1", "EUR", longReference).code()).isEqualTo("VALIDATION_FAILED");
        assertThat(balance(alice, from)).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void anUnavailableReceiverLooksTheSameAsAMissingOne() {
        String alice = newUser();
        String from = createActiveAccount(alice, "EUR");
        deposit(from, "100");
        String notActivated = createAccount(newUser(), "EUR");
        String usd = createActiveAccount(newUser(), "USD");

        Response missing = transfer(alice, key(), from, "EB00000000000000000000000", "1");
        Response inactive = transfer(alice, key(), from, notActivated, "1");
        Response wrongCurrency = transfer(alice, key(), from, usd, "1");

        assertThat(List.of(missing.code(), inactive.code(), wrongCurrency.code())).containsOnly("RECEIVER_NOT_AVAILABLE");
        assertThat(inactive.body().get("detail")).isEqualTo(missing.body().get("detail"));
    }

    @Test
    void notActiveSenderCannotSend() {
        String alice = newUser();
        String from = createActiveAccount(alice, "EUR");
        String to = createActiveAccount(newUser(), "EUR");
        deposit(from, "100");
        as(alice, "PATCH", "/api/v1/accounts/" + from + "/disable", null);

        assertThat(transfer(alice, key(), from, to, "1").code()).isEqualTo("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void depositIsAdminOnly() {
        String alice = newUser();
        String account = createActiveAccount(alice, "EUR");

        Response response = call("POST", TRANSACTIONS + "/deposits", alice, null, key(),
                Map.of("receiverAccountId", account, "amount", BigDecimal.TEN, "currency", "EUR"));

        assertThat(response.status()).isEqualTo(403);
        assertThat(balance(alice, account)).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void balanceIsOnlyVisibleToTheOwnerAndAdmins() {
        String alice = newUser();
        String account = createActiveAccount(alice, "EUR");
        deposit(account, "5");
        String path = "/api/v1/accounts/" + account + "/balance";

        assertThat(as(newUser(), "GET", path, null).status()).isEqualTo(404);
        assertThat(as(alice, "GET", path, null).body().get("amount").decimalValue()).isEqualTo(new BigDecimal("5.00"));
        assertThat(asAdmin("GET", path, null).status()).isEqualTo(200);
    }

    @Test
    void searchCombinesFiltersAndStaysWithinTheCallersAccounts() {
        String alice = newUser();
        String bob = newUser();
        String carol = newUser();
        String a = createActiveAccount(alice, "EUR");
        String b = createActiveAccount(bob, "EUR");
        String c = createActiveAccount(carol, "EUR");
        deposit(a, "100");
        deposit(c, "100");
        String aToB = transfer(alice, key(), a, b, "1").body().get("transactionId").asString();
        transfer(alice, key(), a, c, "2");
        String cToB = transfer(carol, key(), c, b, "3").body().get("transactionId").asString();

        assertThat(ids(as(alice, "GET", TRANSACTIONS + "?transactionId=" + aToB, null))).containsExactly(aToB);
        assertThat(ids(as(alice, "GET", TRANSACTIONS + "?sender=" + a + "&receiver=" + b, null))).containsExactly(aToB);
        assertThat(ids(as(bob, "GET", TRANSACTIONS + "?receiver=" + b, null))).containsExactlyInAnyOrder(aToB, cToB);
        assertThat(ids(as(bob, "GET", TRANSACTIONS + "?accountId=" + b + "&sender=" + c, null))).containsExactly(cToB);

        // Alice asks about transfers she has no part in: searching cannot widen what she sees.
        assertThat(ids(as(alice, "GET", TRANSACTIONS + "?transactionId=" + cToB, null))).isEmpty();
        assertThat(ids(as(alice, "GET", TRANSACTIONS + "?sender=" + c + "&receiver=" + b, null))).isEmpty();
        assertThat(as(alice, "GET", TRANSACTIONS + "/" + cToB, null).status()).isEqualTo(404);
        assertThat(ids(as(newUser(), "GET", TRANSACTIONS, null))).isEmpty();

        assertThat(ids(asAdmin("GET", TRANSACTIONS + "?transactionId=" + cToB, null))).containsExactly(cToB);
        assertThat(asAdmin("GET", TRANSACTIONS + "/" + cToB, null).status()).isEqualTo(200);
    }

    @Test
    void accountWithABalanceCannotBeClosed() {
        String alice = newUser();
        String account = createActiveAccount(alice, "EUR");
        String other = createActiveAccount(newUser(), "EUR");
        deposit(account, "10");
        String path = "/api/v1/accounts/" + account;
        as(alice, "PATCH", path + "/disable", null);

        Response refused = as(alice, "PATCH", path + "/close", null);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("ACCOUNT_BALANCE_NOT_ZERO");
        assertThat(as(alice, "GET", path, null).body().get("status").asString()).isEqualTo("DISABLED");

        asAdmin("PATCH", path + "/activate", null);
        assertThat(transfer(alice, key(), account, other, "10").status()).isEqualTo(201);
        as(alice, "PATCH", path + "/disable", null);
        assertThat(as(alice, "PATCH", path + "/close", null).status()).isEqualTo(200);

        // A closed account cannot receive money either.
        assertThat(transfer(newUser(), key(), other, account, "1").status()).isEqualTo(404);
    }

    /** The project's non-negotiable rule: two simultaneous debits on one account. */
    @Test
    void simultaneousDebitsNeverOverdrawTheAccount() throws Exception {
        String alice = newUser();
        String bob = newUser();
        String from = createActiveAccount(alice, "EUR");
        String to = createActiveAccount(bob, "EUR");
        deposit(from, "100");

        List<Response> responses = runTogether(30, i -> transfer(alice, key(), from, to, "10"));

        assertThat(responses.stream().filter(r -> r.status() == 201)).hasSize(10);
        assertThat(responses.stream().filter(r -> r.status() == 422 && r.code().equals("INSUFFICIENT_FUNDS"))).hasSize(20);
        assertThat(balance(alice, from)).isEqualTo(new BigDecimal("0.00"));
        assertThat(balance(bob, to)).isEqualTo(new BigDecimal("100.00"));
        assertLedgerIsBalanced();
    }

    @Test
    void exactlyTwoSimultaneousDebitsOfTheWholeBalanceOnlyOneWins() throws Exception {
        for (int round = 0; round < 5; round++) {
            String alice = newUser();
            String from = createActiveAccount(alice, "EUR");
            String to = createActiveAccount(newUser(), "EUR");
            deposit(from, "50");

            List<Response> responses = runTogether(2, i -> transfer(alice, key(), from, to, "50"));

            assertThat(responses.stream().map(Response::status)).containsExactlyInAnyOrder(201, 422);
            assertThat(balance(alice, from)).isEqualTo(new BigDecimal("0.00"));
        }
    }

    @Test
    void transfersInOppositeDirectionsDoNotDeadlock() throws Exception {
        String alice = newUser();
        String bob = newUser();
        String a = createActiveAccount(alice, "EUR");
        String b = createActiveAccount(bob, "EUR");
        deposit(a, "1000");
        deposit(b, "1000");

        List<Response> responses = runTogether(40, i -> i % 2 == 0
                ? transfer(alice, key(), a, b, "1")
                : transfer(bob, key(), b, a, "1"));

        assertThat(responses).allMatch(r -> r.status() == 201);
        assertThat(balance(alice, a)).isEqualTo(new BigDecimal("1000.00"));
        assertThat(balance(bob, b)).isEqualTo(new BigDecimal("1000.00"));
    }

    @Test
    void simultaneousRetriesWithOneKeyMoveMoneyOnce() throws Exception {
        String alice = newUser();
        String from = createActiveAccount(alice, "EUR");
        String to = createActiveAccount(newUser(), "EUR");
        deposit(from, "100");
        String key = key();

        List<Response> responses = runTogether(20, i -> transfer(alice, key, from, to, "10"));

        assertThat(responses.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.status() == 200)).hasSize(19);
        assertThat(balance(alice, from)).isEqualTo(new BigDecimal("90.00"));
    }

    @Test
    void closingAnEmptyAccountDuringIncomingTransfersLeavesNoMoneyStranded() throws Exception {
        for (int round = 0; round < 5; round++) {
            String alice = newUser();
            String bob = newUser();
            String sender = createActiveAccount(alice, "EUR");
            String target = createActiveAccount(bob, "EUR");
            deposit(sender, "10");
            as(bob, "PATCH", "/api/v1/accounts/" + target + "/disable", null);

            List<Response> responses = runTogether(2, i -> i == 0
                    ? as(bob, "PATCH", "/api/v1/accounts/" + target + "/close", null)
                    : transfer(alice, key(), sender, target, "10"));

            assertThat(responses.get(0).status()).isEqualTo(200);
            assertThat(responses.get(1).code()).isEqualTo("RECEIVER_NOT_AVAILABLE");
            assertThat(balance(bob, target)).isEqualTo(new BigDecimal("0.00"));
        }
    }

    private void assertLedgerIsBalanced() {
        Integer unbalanced = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT transfer_id FROM ledger_entries GROUP BY transfer_id
                    HAVING sum(CASE WHEN type = 'CREDIT' THEN amount ELSE -amount END) <> 0 OR count(*) <> 2
                ) broken
                """, Integer.class);
        assertThat(unbalanced).isZero();
        BigDecimal total = jdbc.queryForObject(
                "SELECT coalesce(sum(CASE WHEN type = 'CREDIT' THEN amount ELSE -amount END), 0) FROM ledger_entries",
                BigDecimal.class);
        assertThat(total).isEqualByComparingTo(BigDecimal.ZERO);
        Integer doneWithoutEntries = jdbc.queryForObject("""
                SELECT count(*) FROM transfers t WHERE t.status = 'DONE'
                AND NOT EXISTS (SELECT 1 FROM ledger_entries e WHERE e.transfer_id = t.id)
                """, Integer.class);
        assertThat(doneWithoutEntries).isZero();
        Integer cancelledWithEntries = jdbc.queryForObject("""
                SELECT count(*) FROM transfers t WHERE t.status <> 'DONE'
                AND EXISTS (SELECT 1 FROM ledger_entries e WHERE e.transfer_id = t.id)
                """, Integer.class);
        assertThat(cancelledWithEntries).isZero();
    }

    private static List<String> ids(Response response) {
        assertThat(response.status()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        response.body().get("items").forEach(item -> ids.add(item.get("transactionId").asString()));
        return ids;
    }

    private interface Call {
        Response run(int index);
    }

    /** Releases all calls at the same moment and returns the responses in call order. */
    private static List<Response> runTogether(int count, Call call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<Response> task = () -> {
                    start.await();
                    return call.run(index);
                };
                futures.add(executor.submit(task));
            }
            start.countDown();
            List<Response> responses = new ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        }
    }
}
