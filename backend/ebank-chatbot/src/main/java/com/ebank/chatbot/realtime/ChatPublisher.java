package com.ebank.chatbot.realtime;

import com.ebank.realtime.api.RealtimeMessaging;
import com.ebank.realtime.api.UserQueue;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Live chat events on {@code /user/queue/chat}. Sent after the commit, so nobody is told about a
 * message that then rolls back. Best effort: clients catch up with {@code GET .../messages?afterSeq=}.
 */
@Component
public class ChatPublisher {

    public static final UserQueue CHAT = new UserQueue("/queue/chat");

    private static final Logger log = LoggerFactory.getLogger(ChatPublisher.class);

    private final RealtimeMessaging realtime;

    ChatPublisher(RealtimeMessaging realtime) {
        this.realtime = realtime;
    }

    public void afterCommit(Collection<String> userIds, Object event) {
        Set<String> recipients = new LinkedHashSet<>(userIds);
        recipients.removeIf(Objects::isNull);
        Runnable send = () -> recipients.forEach(userId -> {
            try {
                realtime.send(userId, CHAT, event);
            } catch (RuntimeException ex) {
                log.warn("Chat push failed: {}", ex.getClass().getSimpleName());
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    public boolean isInChat(String userId) {
        return realtime.isSubscribed(userId, CHAT);
    }

    /** Advisors with the chat open: the ones who can take a conversation right now. */
    public Set<String> onlineAdvisors() {
        return realtime.subscribedUsersWithRole(CHAT, "ADVISOR");
    }

    @Configuration(proxyBeanMethods = false)
    static class Queue {
        @Bean
        UserQueue chatQueue() {
            return CHAT;
        }
    }
}
