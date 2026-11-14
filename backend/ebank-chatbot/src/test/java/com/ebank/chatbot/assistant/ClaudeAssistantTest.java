package com.ebank.chatbot.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import com.ebank.chatbot.ChatbotProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ClaudeAssistantTest {

    private final AnthropicClient client = mock(AnthropicClient.class);
    private final MessageService messages = mock(MessageService.class);
    private final AssistantTools tools = mock(AssistantTools.class);
    private final AssistantTools.Session session = mock(AssistantTools.Session.class);
    private ClaudeAssistant assistant;
    private final UUID conversation = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(client.messages()).thenReturn(messages);
        when(tools.session("alice", conversation)).thenReturn(session);
        when(tools.definitions()).thenReturn(new AssistantTools(null, null, null, null, new SimpleMeterRegistry()).definitions());
        when(session.escalationReason()).thenReturn(java.util.Optional.empty());
        ChatbotProperties.Assistant settings = new ChatbotProperties.Assistant("key", "claude-opus-5-5", "low", Duration.ofSeconds(5), 3, 30);
        assistant = new ClaudeAssistant(client, tools, settings, new SimpleMeterRegistry());
    }

    /** Built from the JSON the API returns, so the test also covers the SDK's parsing. */
    private static Message message(String stopReason, String... contentJson) {
        String json = """
                {"id": "msg_%s", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "content": [%s], "stop_reason": "%s", "stop_sequence": null,
                 "usage": {"input_tokens": 10, "output_tokens": 5}}
                """.formatted(UUID.randomUUID(), String.join(",", contentJson), stopReason);
        try {
            return ObjectMappers.jsonMapper().readValue(json, Message.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String text(String text) {
        return "{\"type\": \"text\", \"text\": \"" + text + "\"}";
    }

    private static String toolUse(String id, String name, String inputJson) {
        return "{\"type\": \"tool_use\", \"id\": \"" + id + "\", \"name\": \"" + name + "\", \"input\": " + inputJson + "}";
    }

    @Test
    void runsRequestedToolsAndReturnsTheFinalAnswer() {
        when(messages.create(any(MessageCreateParams.class))).thenReturn(
                message("tool_use", text("Let me check."),
                        toolUse("tu_1", "list_accounts", "{}"),
                        toolUse("tu_2", "get_balance", "{\"account_ref\": \"account_1\"}")),
                message("end_turn", text("Your current account ending 2479 holds 105.00 EUR.")));
        when(session.call(any(), anyMap())).thenReturn(new AssistantTools.Result("{\"ok\":true}", false));

        Assistant.Reply reply = assistant.reply("alice", conversation, List.of(new Turn(Turn.Role.CUSTOMER, "What's my balance?")));

        assertThat(reply.text()).isEqualTo("Your current account ending 2479 holds 105.00 EUR.");
        verify(session).call(eq("list_accounts"), anyMap());
        verify(session).call(eq("get_balance"), eq(Map.of("account_ref", "account_1")));

        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messages, times(2)).create(sent.capture());
        MessageCreateParams first = sent.getAllValues().get(0);
        assertThat(first.model().asString()).isEqualTo("claude-opus-5-5");
        assertThat(first.tools().orElseThrow()).hasSize(4);
        assertThat(first.toolChoice()).isEmpty();
        // Second request: user, the assistant's tool-use turn, then ONE user message holding both results.
        List<com.anthropic.models.messages.MessageParam> second = sent.getAllValues().get(1).messages();
        assertThat(second).hasSize(3);
        assertThat(second.get(2).user().orElseThrow().content().asContentBlockParams()).hasSize(2)
                .allSatisfy(block -> assertThat(block.isToolResult()).isTrue());
    }

    @Test
    void stopsAfterTheToolTurnLimit() {
        when(messages.create(any(MessageCreateParams.class))).thenReturn(
                message("tool_use", toolUse("tu", "list_accounts", "{}")));
        when(session.call(any(), anyMap())).thenReturn(new AssistantTools.Result("{}", false));

        Assistant.Reply reply = assistant.reply("alice", conversation, List.of(new Turn(Turn.Role.CUSTOMER, "loop")));

        assertThat(reply.text()).isEqualTo(ClaudeAssistant.GAVE_UP_TEXT);
        verify(messages, times(3)).create(any(MessageCreateParams.class));
    }

    @Test
    void refusalGetsANeutralAnswer() {
        when(messages.create(any(MessageCreateParams.class))).thenReturn(message("refusal"));

        assertThat(assistant.reply("alice", conversation, List.of(new Turn(Turn.Role.CUSTOMER, "x"))).text())
                .isEqualTo(ClaudeAssistant.REFUSAL_TEXT);
    }

    @Test
    void historyStartsWithTheCustomer() {
        when(messages.create(any(MessageCreateParams.class))).thenReturn(message("end_turn", text("Hi")));

        assistant.reply("alice", conversation, List.of(
                new Turn(Turn.Role.ASSISTANT, "welcome"), new Turn(Turn.Role.CUSTOMER, "hello")));

        ArgumentCaptor<MessageCreateParams> sent = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messages).create(sent.capture());
        assertThat(sent.getValue().messages()).hasSize(1);
        assertThat(sent.getValue().messages().get(0).isUser()).isTrue();
    }
}
