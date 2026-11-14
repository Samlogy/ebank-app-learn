package com.ebank.chatbot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param assistant the LLM. Without an API key, a scripted assistant answers instead (dev and tests).
 * @param limits per-user rate limits
 */
@ConfigurationProperties("ebank.chatbot")
public record ChatbotProperties(@DefaultValue Assistant assistant, @DefaultValue Limits limits) {

    /**
     * @param apiKey Anthropic API key; empty means the scripted assistant
     * @param effort low suits short chat answers; raise it only if measured quality needs it
     * @param maxToolTurns tool round trips per reply, so a confused model cannot loop forever
     * @param historySize messages sent as context
     */
    public record Assistant(
            String apiKey,
            @DefaultValue("claude-opus-5-5") String model,
            @DefaultValue("low") String effort,
            @DefaultValue("60s") Duration timeout,
            @DefaultValue("6") int maxToolTurns,
            @DefaultValue("30") int historySize) {

        public boolean usesClaude() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Limits(
            @DefaultValue("20") int messagesPerMinute,
            @DefaultValue("10") int conversationsPerHour,
            @DefaultValue("5") int handoffsPerHour) {}
}
