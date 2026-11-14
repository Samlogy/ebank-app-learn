package com.ebank.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;

class NotificationsIT extends IntegrationTestBase {

    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired JdbcTemplate jdbc;
    @Autowired SimpUserRegistry users;

    private void deposit(String accountId, String amount) {
        Response response = call("POST", "/api/v1/transactions/deposits", "admin-user", "ADMIN", UUID.randomUUID().toString(),
                Map.of("receiverAccountId", accountId, "amount", new BigDecimal(amount), "currency", "EUR"));
        assertThat(response.status()).isEqualTo(201);
    }

    private String transfer(String userId, String from, String to, String amount) {
        Response response = sendTransfer(userId, from, to, amount);
        assertThat(response.status()).isEqualTo(201);
        return response.body().get("transactionId").asString();
    }

    private Response sendTransfer(String userId, String from, String to, String amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("senderAccountId", from);
        body.put("receiverAccountId", to);
        body.put("amount", new BigDecimal(amount));
        body.put("currency", "EUR");
        body.put("reference", "rent");
        return call("POST", "/api/v1/transactions", userId, null, UUID.randomUUID().toString(), body);
    }

    private List<JsonNode> notificationsOf(String userId) {
        Response response = as(userId, "GET", NOTIFICATIONS + "?size=100", null);
        assertThat(response.status()).isEqualTo(200);
        return StreamSupport.stream(response.body().get("items").spliterator(), false).toList();
    }

    /** The outbox relay runs in the background, so notifications appear shortly after the transfer. */
    private JsonNode awaitNotification(String userId, Predicate<JsonNode> match) {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            var found = notificationsOf(userId).stream().filter(match).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            sleep(100);
        }
        throw new AssertionError("No matching notification for " + userId + " within " + WAIT);
    }

    private static Predicate<JsonNode> about(String transactionId, String type) {
        return n -> transactionId.equals(n.path("transactionId").asString()) && type.equals(n.path("type").asString());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void transferNotifiesTheSenderAndTheReceiver() {
        String alice = newUser();
        String bob = newUser();
        String from = createActiveAccount(alice, "EUR");
        String to = createActiveAccount(bob, "EUR");
        deposit(from, "100");

        String transactionId = transfer(alice, from, to, "30.50");

        JsonNode received = awaitNotification(bob, about(transactionId, "TRANSFER_RECEIVED"));
        assertThat(received.get("amount").decimalValue()).isEqualByComparingTo("30.50");
        assertThat(received.get("currency").asString()).isEqualTo("EUR");
        assertThat(received.get("accountId").asString()).isEqualTo(to);
        assertThat(received.get("counterparty").asString()).isEqualTo(from);
        assertThat(received.get("read").asBoolean()).isFalse();

        JsonNode sent = awaitNotification(alice, about(transactionId, "TRANSFER_SENT"));
        assertThat(sent.get("accountId").asString()).isEqualTo(from);

        // Each user only sees their own notifications.
        assertThat(notificationsOf(bob)).noneMatch(n -> "TRANSFER_SENT".equals(n.path("type").asString()));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox_event WHERE aggregate_id = ? AND event_type = 'transactions.TransactionCompleted'",
                String.class, transactionId)).isEqualTo("PUBLISHED");
    }

    @Test
    void cancelledTransferOnlyNotifiesTheSender() {
        String alice = newUser();
        String bob = newUser();
        String from = createActiveAccount(alice, "EUR");
        String to = createActiveAccount(bob, "EUR");

        // Refused with a 422, but the transfer is recorded as CANCELLED and its event is published.
        assertThat(sendTransfer(alice, from, to, "10").code()).isEqualTo("INSUFFICIENT_FUNDS");

        JsonNode cancelled = awaitNotification(alice, n -> "TRANSFER_CANCELLED".equals(n.path("type").asString()));
        assertThat(cancelled.get("accountId").asString()).isEqualTo(from);
        sleep(500);
        assertThat(notificationsOf(bob)).isEmpty();
    }

    @Test
    void readStateBelongsToTheOwner() {
        String bob = newUser();
        String to = createActiveAccount(bob, "EUR");
        deposit(to, "5");
        deposit(to, "6");
        Instant deadline = Instant.now().plus(WAIT);
        while (notificationsOf(bob).size() < 2 && Instant.now().isBefore(deadline)) {
            sleep(100);
        }
        String id = notificationsOf(bob).getFirst().get("id").asString();
        assertThat(as(bob, "GET", NOTIFICATIONS + "/unread-count", null).body().get("count").asLong()).isEqualTo(2);

        assertThat(as(newUser(), "PATCH", NOTIFICATIONS + "/" + id + "/read", null).status()).isEqualTo(404);

        Response read = as(bob, "PATCH", NOTIFICATIONS + "/" + id + "/read", null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.body().get("read").asBoolean()).isTrue();
        assertThat(as(bob, "GET", NOTIFICATIONS + "?unreadOnly=true", null).body().get("totalElements").asLong()).isEqualTo(1);

        assertThat(as(bob, "POST", NOTIFICATIONS + "/read-all", null).body().get("updated").asInt()).isEqualTo(1);
        assertThat(as(bob, "GET", NOTIFICATIONS + "/unread-count", null).body().get("count").asLong()).isZero();
    }

    @Test
    void preferencesDefaultToEmailAndCanBeChanged() {
        String user = newUser();

        Response defaults = as(user, "GET", NOTIFICATIONS + "/preferences", null);
        assertThat(defaults.body().get("emailEnabled").asBoolean()).isTrue();
        assertThat(defaults.body().get("smsEnabled").asBoolean()).isFalse();

        Response saved = as(user, "PUT", NOTIFICATIONS + "/preferences", Map.of("emailEnabled", false, "smsEnabled", true));
        assertThat(saved.status()).isEqualTo(200);
        assertThat(as(user, "GET", NOTIFICATIONS + "/preferences", null).body().get("smsEnabled").asBoolean()).isTrue();

        assertThat(as(user, "PUT", NOTIFICATIONS + "/preferences", Map.of("emailEnabled", true)).status()).isEqualTo(400);
        assertThat(call("GET", NOTIFICATIONS, null, null, null, null).status()).isEqualTo(401);
    }

    private StompSession connect(String userId) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        StompHeaders connect = new StompHeaders();
        if (userId != null) {
            connect.add("Authorization", "Bearer " + userId + "~");
        }
        return client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect,
                new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
    }

    private boolean subscribed(String userId) {
        SimpUser user = users.getUser(userId);
        return user != null && user.getSessions().stream().anyMatch(s -> !s.getSubscriptions().isEmpty());
    }

    @Test
    void onlineUserGetsTheNotificationPushedLive() throws Exception {
        String alice = newUser();
        String bob = newUser();
        String from = createActiveAccount(alice, "EUR");
        String to = createActiveAccount(bob, "EUR");
        deposit(from, "100");

        StompSession session = connect(bob);
        BlockingQueue<JsonNode> pushed = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                pushed.add((JsonNode) payload);
            }
        });
        // The simple broker sends no RECEIPT for SUBSCRIBE, so wait until the server has registered it.
        Instant deadline = Instant.now().plus(WAIT);
        while (!subscribed(bob) && Instant.now().isBefore(deadline)) {
            sleep(50);
        }
        assertThat(subscribed(bob)).isTrue();

        String transactionId = transfer(alice, from, to, "12");

        JsonNode notification = pushed.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(notification).isNotNull();
        assertThat(notification.get("transactionId").asString()).isEqualTo(transactionId);
        assertThat(notification.get("type").asString()).isEqualTo("TRANSFER_RECEIVED");
        session.disconnect();
    }

    @Test
    void connectWithoutATokenIsRefused() {
        assertThatThrownBy(() -> connect(null)).isInstanceOf(Exception.class);
    }
}
