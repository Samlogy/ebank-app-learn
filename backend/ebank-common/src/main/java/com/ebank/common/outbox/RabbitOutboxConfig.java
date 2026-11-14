package com.ebank.common.outbox;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The RabbitMQ transport of the outbox ({@code ebank.outbox.rabbitmq.enabled=true}).
 *
 * <pre>
 *   outbox_event --RabbitOutboxPublisher--> exchange ebank.events (topic, routing key = event type)
 *        --> queue ebank.&lt;handler&gt;  (one per handler, quorum)  --> RabbitOutboxConsumer --> handler
 *        --> after the retries: exchange ebank.events.dead-letter --> queue ebank.&lt;handler&gt;.dlq
 * </pre>
 *
 * Each handler has its own queue, so handlers stay independent as with the in-process relay, and
 * a handler can later move to another service by consuming the same queue. Retries (back-off,
 * then the dead-letter queue) are the listener settings under spring.rabbitmq.listener.simple.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("ebank.outbox.rabbitmq.enabled")
class RabbitOutboxConfig {

    static final String EVENTS = "ebank.events";
    static final String DEAD_LETTERS = "ebank.events.dead-letter";

    static String queueOf(OutboxEventHandler<?> handler) {
        return "ebank." + handler.handlerName();
    }

    static String deadLetterQueueOf(OutboxEventHandler<?> handler) {
        return queueOf(handler) + ".dlq";
    }

    /** Declared by RabbitAdmin when the first connection opens. */
    @Bean
    Declarables outboxTopology(List<OutboxEventHandler<?>> handlers) {
        TopicExchange events = new TopicExchange(EVENTS, true, false);
        DirectExchange deadLetters = new DirectExchange(DEAD_LETTERS, true, false);
        List<Declarable> declarables = new ArrayList<>(List.of(events, deadLetters));
        for (OutboxEventHandler<?> handler : handlers) {
            // Quorum queues: replicated and durable, the RabbitMQ default for data that matters.
            Queue queue = QueueBuilder.durable(queueOf(handler)).quorum()
                    .deadLetterExchange(DEAD_LETTERS)
                    .deadLetterRoutingKey(deadLetterQueueOf(handler))
                    .build();
            Queue deadLetterQueue = QueueBuilder.durable(deadLetterQueueOf(handler)).quorum().build();
            Binding binding = BindingBuilder.bind(queue).to(events).with(handler.eventType());
            Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue).to(deadLetters).with(deadLetterQueueOf(handler));
            declarables.addAll(List.of(queue, deadLetterQueue, binding, deadLetterBinding));
        }
        return new Declarables(declarables);
    }

    /** One listener container per handler queue, built by Boot's factory (retry and ack settings). */
    @Bean
    RabbitListenerConfigurer outboxListeners(
            List<OutboxEventHandler<?>> handlers, OutboxStore store, SimpleRabbitListenerContainerFactory factory) {
        return (RabbitListenerEndpointRegistrar registrar) -> {
            for (OutboxEventHandler<?> handler : handlers) {
                SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
                endpoint.setId("outbox-" + handler.handlerName());
                endpoint.setQueueNames(queueOf(handler));
                endpoint.setMessageListener(new RabbitOutboxConsumer(store, handler));
                registrar.registerEndpoint(endpoint, factory);
            }
        };
    }
}
