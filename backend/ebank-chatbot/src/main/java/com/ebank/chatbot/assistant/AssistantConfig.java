package com.ebank.chatbot.assistant;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.ebank.chatbot.ChatbotProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Claude when {@code ebank.chatbot.assistant.api-key} is set, the scripted assistant otherwise. */
@Configuration(proxyBeanMethods = false)
class AssistantConfig {

    private static final Logger log = LoggerFactory.getLogger(AssistantConfig.class);

    @Bean
    Assistant assistant(ChatbotProperties properties, AssistantTools tools, JsonMapper json, MeterRegistry meters) {
        ChatbotProperties.Assistant settings = properties.assistant();
        if (!settings.usesClaude()) {
            log.warn("No Anthropic API key: the chat uses the scripted assistant");
            return new ScriptedAssistant(tools, json);
        }
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(settings.apiKey())
                .timeout(settings.timeout())
                .maxRetries(2)
                .build();
        log.info("Chat assistant uses {} with effort {}", settings.model(), settings.effort());
        return new ClaudeAssistant(client, tools, settings, meters);
    }
}
