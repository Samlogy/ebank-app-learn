package com.ebank.chatbot;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatbotProperties.class)
class ChatbotConfig {

    /** Assistant replies and summaries run here: virtual threads, since they mostly wait on HTTP. */
    @Bean(destroyMethod = "close")
    ExecutorService chatbotExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
