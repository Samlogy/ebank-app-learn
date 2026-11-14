package com.ebank.notifications.infrastructure.sms;

import com.ebank.notifications.application.NotificationText;
import com.ebank.notifications.application.OfflineChannel;
import com.ebank.notifications.domain.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * There is no SMS provider yet. This stands in for one so preferences and the fallback flow can
 * be built and tested: it logs that a message would be sent, without the number or the text.
 * Replace it with a provider adapter (Twilio, Vonage...) implementing {@link OfflineChannel}.
 */
@Component
class LoggingSmsChannel implements OfflineChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsChannel.class);

    @Override
    public Kind kind() {
        return Kind.SMS;
    }

    @Override
    public void send(Notification notification, String address) {
        log.info("SMS for notification {} not sent: no SMS provider configured ({} characters)",
                notification.getId(), NotificationText.sms(notification).length());
    }
}
