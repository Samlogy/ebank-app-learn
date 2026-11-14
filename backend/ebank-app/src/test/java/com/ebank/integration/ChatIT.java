package com.ebank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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

/** Customer, scripted assistant and advisor, over REST and STOMP, on a real database. */
class ChatIT extends IntegrationTestBase {

    private static final String CHAT = "/api/v1/chat/conversations";
    private static final String ADVISOR = "/api/v1/advisor";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired JdbcTemplate jdbc;
    @Autowired SimpUserRegistry users;

    private Response asAdvisor(String advisorId, String method, String path, Object body) {
        return call(method, path, advisorId, "ADVISOR", null, body);
    }

    private static String newAdvisor() {
        return "advisor-" + UUID.randomUUID();
    }

    private String open(String userId) {
        Response opened = as(userId, "POST", CHAT, null);
        assertThat(opened.status()).isEqualTo(200);
        return opened.body().get("id").asString();
    }

    private Response send(String userId, String conversation, String clientId, String text) {
        return as(userId, "POST", CHAT + "/" + conversation + "/messages", Map.of("clientMessageId", clientId, "text", text));
    }

    private static String clientId() {
        return UUID.randomUUID().toString();
    }

    private List<JsonNode> messages(String userId, String conversation) {
        Response response = as(userId, "GET", CHAT + "/" + conversation + "/messages?limit=100", null);
        assertThat(response.status()).isEqualTo(200);
        return StreamSupport.stream(response.body().spliterator(), false).toList();
    }

    private JsonNode await(String what, java.util.function.Supplier<List<JsonNode>> source, Predicate<JsonNode> match) {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            var found = source.get().stream().filter(match).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private String awaitState(String userId, String conversation, String state) {
        Instant deadline = Instant.now().plus(WAIT);
        String current = null;
        while (Instant.now().isBefore(deadline)) {
            current = as(userId, "GET", CHAT + "/" + conversation, null).body().get("state").asString();
            if (state.equals(current)) {
                return current;
            }
            sleep(100);
        }
        throw new AssertionError("State stayed " + current + ", expected " + state);
    }

    private static Predicate<JsonNode> from(String senderType) {
        return m -> senderType.equals(m.path("senderType").asString());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private void deposit(String accountId, String amount) {
        Response response = call("POST", "/api/v1/transactions/deposits", "admin-user", "ADMIN", UUID.randomUUID().toString(),
                Map.of("receiverAccountId", accountId, "amount", new BigDecimal(amount), "currency", "EUR"));
        assertThat(response.status()).isEqualTo(201);
    }

    @Test
    void assistantAnswersFromTheCustomersOwnDataWithoutAccountNumbers() {
        String alice = newUser();
        String account = createActiveAccount(alice, "EUR");
        deposit(account, "100");
        String conversation = open(alice);
        assertThat(open(alice)).as("one open conversation per customer").isEqualTo(conversation);
        assertThat(messages(alice, conversation)).singleElement().satisfies(m -> assertThat(m.path("senderType").asString()).isEqualTo("SYSTEM"));

        String key = clientId();
        Response sent = send(alice, conversation, key, "What is my balance?");
        assertThat(sent.status()).isEqualTo(201);
        Response retry = send(alice, conversation, key, "What is my balance?");
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().get("id").asString()).isEqualTo(sent.body().get("id").asString());

        JsonNode answer = await("the assistant's answer", () -> messages(alice, conversation), from("ASSISTANT"));
        assertThat(answer.get("content").asString())
                .contains("100.00 EUR", "ending " + account.substring(account.length() - 4))
                .doesNotContain(account);
        assertThat(messages(alice, conversation)).filteredOn(from("CUSTOMER")).hasSize(1);
        assertThat(jdbc.queryForList("SELECT detail FROM chatbot_audit WHERE conversation_id = ?::uuid AND action = 'TOOL_CALL'",
                String.class, conversation)).contains("list_accounts:ok", "get_balance:ok");
    }

    @Test
    void customersOnlySeeTheirOwnConversations() {
        String conversation = open(newUser());
        String bob = newUser();

        assertThat(as(bob, "GET", CHAT + "/" + conversation, null).status()).isEqualTo(404);
        assertThat(as(bob, "GET", CHAT + "/" + conversation + "/messages", null).status()).isEqualTo(404);
        assertThat(send(bob, conversation, clientId(), "hello").status()).isEqualTo(404);
        assertThat(as(bob, "POST", CHAT + "/" + conversation + "/handoff", null).status()).isEqualTo(404);
        assertThat(call("GET", CHAT, null, null, null, null).status()).isEqualTo(401);
    }

    @Test
    void handoffQueueClaimChatAndResolve() {
        String alice = newUser();
        String conversation = open(alice);
        send(alice, conversation, clientId(), "My card was stolen yesterday");
        await("assistant reply", () -> messages(alice, conversation), from("ASSISTANT"));

        Response handoff = as(alice, "POST", CHAT + "/" + conversation + "/handoff", null);
        assertThat(handoff.body().get("state").asString()).isEqualTo("WAITING_ADVISOR");
        assertThat(handoff.body().get("handoffReason").asString()).isEqualTo("USER_REQUEST");
        await("the no-advisor notice", () -> messages(alice, conversation),
                m -> m.path("content").asString().startsWith("No advisor is online"));

        assertThat(as(alice, "GET", ADVISOR + "/queue", null).status()).as("customers have no advisor access").isEqualTo(403);
        String advisor = newAdvisor();
        JsonNode queued = await("the queue entry", () -> StreamSupport.stream(
                        asAdvisor(advisor, "GET", ADVISOR + "/queue", null).body().spliterator(), false).toList(),
                c -> conversation.equals(c.path("id").asString()) && !c.path("summary").isNull());
        assertThat(queued.get("summary").asString()).contains("My card was stolen yesterday");
        assertThat(StreamSupport.stream(asAdvisor(advisor, "GET", ADVISOR + "/conversations/" + conversation + "/messages", null)
                .body().spliterator(), false).toList()).as("the full transcript, bot part included").anyMatch(from("ASSISTANT"));

        Response claim = asAdvisor(advisor, "POST", ADVISOR + "/conversations/" + conversation + "/claim", null);
        assertThat(claim.status()).isEqualTo(200);
        assertThat(claim.body().get("state").asString()).isEqualTo("WITH_ADVISOR");
        String other = newAdvisor();
        assertThat(asAdvisor(other, "POST", ADVISOR + "/conversations/" + conversation + "/claim", null).status()).isEqualTo(404);
        assertThat(asAdvisor(other, "GET", ADVISOR + "/conversations/" + conversation, null).status()).isEqualTo(404);
        assertThat(asAdvisor(advisor, "GET", ADVISOR + "/conversations", null).body().get(0).get("id").asString()).isEqualTo(conversation);

        Response reply = asAdvisor(advisor, "POST", ADVISOR + "/conversations/" + conversation + "/messages",
                Map.of("clientMessageId", clientId(), "text", "I have blocked your card. A new one is on its way."));
        assertThat(reply.status()).isEqualTo(201);
        await("the advisor's message", () -> messages(alice, conversation), from("ADVISOR"));
        // alice is not in the chat: she gets a notification, without the message text.
        JsonNode notification = await("the advisor-message notification", () -> StreamSupport.stream(
                        as(alice, "GET", "/api/v1/notifications", null).body().get("items").spliterator(), false).toList(),
                n -> "ADVISOR_MESSAGE".equals(n.path("type").asString()));
        assertThat(notification.get("transactionId").asString()).isEqualTo(conversation);

        assertThat(send(alice, conversation, clientId(), "Thank you!").status()).isEqualTo(201);
        sleep(500);
        assertThat(messages(alice, conversation)).as("no assistant reply while with an advisor").filteredOn(from("ASSISTANT")).hasSize(1);

        assertThat(asAdvisor(advisor, "POST", ADVISOR + "/conversations/" + conversation + "/resolve", null).body()
                .get("state").asString()).isEqualTo("RESOLVED");
        assertThat(send(alice, conversation, clientId(), "One more thing").code()).isEqualTo("CONVERSATION_RESOLVED");
        assertThat(as(alice, "POST", CHAT + "/" + conversation + "/back-to-bot", null).body().get("state").asString()).isEqualTo("BOT");
        assertThat(jdbc.queryForList("SELECT action FROM chatbot_audit WHERE conversation_id = ?::uuid", String.class, conversation))
                .contains("HANDOFF", "ASSIGN", "RESOLVE", "BACK_TO_BOT");
    }

    @Test
    void assistantEscalatesWhenAskedForAPerson() {
        String alice = newUser();
        String conversation = open(alice);

        send(alice, conversation, clientId(), "I want to talk to a human please");

        awaitState(alice, conversation, "WAITING_ADVISOR");
        assertThat(as(alice, "GET", CHAT + "/" + conversation, null).body().get("handoffReason").asString()).isEqualTo("LLM_ESCALATION");
        assertThat(jdbc.queryForList("SELECT detail FROM chatbot_audit WHERE conversation_id = ?::uuid AND action = 'TOOL_CALL'",
                String.class, conversation)).contains("escalate_to_advisor:ok");
    }

    @Test
    void onlineAdvisorGetsTheConversationAssignedLive() throws Exception {
        String advisor = newAdvisor();
        BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + advisor + "~ADVISOR");
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect,
                new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
        session.subscribe("/user/queue/chat", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                events.add((JsonNode) payload);
            }
        });
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            SimpUser user = users.getUser(advisor);
            if (user != null && user.getSessions().stream().anyMatch(s -> !s.getSubscriptions().isEmpty())) {
                break;
            }
            sleep(50);
        }
        try {
            String alice = newUser();
            String conversation = open(alice);
            as(alice, "POST", CHAT + "/" + conversation + "/handoff", null);

            awaitState(alice, conversation, "WITH_ADVISOR");
            assertThat(asAdvisor(advisor, "GET", ADVISOR + "/conversations/" + conversation, null).body()
                    .get("state").asString()).isEqualTo("WITH_ADVISOR");
            JsonNode joined = null;
            Instant until = Instant.now().plus(WAIT);
            while (joined == null && Instant.now().isBefore(until)) {
                JsonNode event = events.poll(1, TimeUnit.SECONDS);
                if (event != null && "MESSAGE".equals(event.path("type").asString())
                        && "An advisor joined the conversation.".equals(event.path("message").path("content").asString())) {
                    joined = event;
                }
            }
            assertThat(joined).as("the advisor is pushed the conversation they were given").isNotNull();
            assertThat(joined.get("conversationId").asString()).isEqualTo(conversation);
        } finally {
            session.disconnect();
        }
    }

    @Test
    void messagesAreRateLimited() {
        String alice = newUser();
        String conversation = open(alice);
        int status = 0;
        for (int i = 0; i < 21 && status != 429; i++) {
            Response response = send(alice, conversation, clientId(), "hello " + i);
            status = response.status();
        }
        assertThat(status).isEqualTo(429);
    }

    @Test
    void readReceiptsDeletionAndValidation() {
        String alice = newUser();
        String conversation = open(alice);
        JsonNode mine = send(alice, conversation, clientId(), "hello").body();
        JsonNode assistant = await("assistant reply", () -> messages(alice, conversation), from("ASSISTANT"));

        assertThat(as(alice, "POST", CHAT + "/" + conversation + "/read", Map.of("seq", assistant.get("seq").asLong())).status()).isEqualTo(204);
        assertThat(as(alice, "DELETE", CHAT + "/" + conversation + "/messages/" + assistant.get("id").asString(), null).status())
                .as("only your own messages").isEqualTo(404);
        JsonNode deleted = as(alice, "DELETE", CHAT + "/" + conversation + "/messages/" + mine.get("id").asString(), null).body();
        assertThat(deleted.get("deleted").asBoolean()).isTrue();
        assertThat(deleted.get("content").asString()).isEmpty();

        assertThat(send(alice, conversation, clientId(), " ").status()).isEqualTo(400);
        assertThat(send(alice, conversation, clientId(), "x".repeat(2001)).status()).isEqualTo(400);
        assertThat(send(alice, conversation, "bad id!", "hello").status()).isEqualTo(400);
    }
}
