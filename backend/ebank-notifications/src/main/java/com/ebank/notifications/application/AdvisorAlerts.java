package com.ebank.notifications.application;

import com.ebank.chatbot.api.HandoffRequested;
import com.ebank.notifications.application.OfflineChannel.Kind;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Emails advisors when a customer joins the queue while none of them is online. At most one alert
 * per {@link #QUIET_PERIOD}, so a busy evening does not flood their inbox. Nothing about the
 * customer is included.
 */
@Service
public class AdvisorAlerts {

    static final Duration QUIET_PERIOD = Duration.ofMinutes(5);
    static final String ROLE = "ADVISOR";
    static final String SUBJECT = "EBANK: a customer is waiting for an advisor";
    static final String BODY = "A customer is waiting in the advisor queue and no advisor is online.\n\n"
            + "Sign in to the EBANK advisor workspace to take the conversation.";

    private static final Logger log = LoggerFactory.getLogger(AdvisorAlerts.class);

    private final UserDirectory directory;
    private final Optional<OfflineChannel> email;
    private final Clock clock;
    private final AtomicReference<Instant> lastAlert = new AtomicReference<>(Instant.EPOCH);

    @Autowired
    AdvisorAlerts(UserDirectory directory, List<OfflineChannel> channels) {
        this(directory, channels, Clock.systemUTC());
    }

    AdvisorAlerts(UserDirectory directory, List<OfflineChannel> channels, Clock clock) {
        this.directory = directory;
        this.email = channels.stream().filter(c -> c.kind() == Kind.EMAIL).findFirst();
        this.clock = clock;
    }

    /** Throws if sending failed, so the outbox retries. */
    public void onHandoff(HandoffRequested event) {
        Instant now = clock.instant();
        Instant previous = lastAlert.get();
        if (now.isBefore(previous.plus(QUIET_PERIOD)) || !lastAlert.compareAndSet(previous, now)) {
            log.debug("Advisor alert for conversation {} skipped: one was sent recently", event.conversationId());
            return;
        }
        if (email.isEmpty()) {
            log.info("Advisor alert skipped: no email channel");
            return;
        }
        List<String> addresses;
        try {
            addresses = directory.usersWithRole(ROLE).stream()
                    .map(UserDirectory.UserContact::email)
                    .filter(address -> address != null && !address.isBlank())
                    .toList();
            addresses.forEach(address -> email.get().sendText(address, SUBJECT, BODY));
        } catch (RuntimeException ex) {
            lastAlert.set(previous);    // the lookup or a send failed: let the outbox retry send it
            throw ex;
        }
        log.info("Advisor alert for conversation {} sent to {} advisor(s)", event.conversationId(), addresses.size());
    }
}
