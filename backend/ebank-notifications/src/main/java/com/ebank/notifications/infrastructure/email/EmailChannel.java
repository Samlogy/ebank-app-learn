package com.ebank.notifications.infrastructure.email;

import com.ebank.notifications.application.NotificationText;
import com.ebank.notifications.application.OfflineChannel;
import com.ebank.notifications.domain.Notification;
import com.ebank.notifications.infrastructure.NotificationsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Plain-text email through SMTP (Mailpit in dev). Only exists when {@code spring.mail.host} is set. */
@Component
@ConditionalOnProperty("spring.mail.host")
class EmailChannel implements OfflineChannel {

    private final JavaMailSender mail;
    private final String from;

    EmailChannel(JavaMailSender mail, NotificationsProperties properties) {
        this.mail = mail;
        this.from = properties.email().from();
    }

    @Override
    public Kind kind() {
        return Kind.EMAIL;
    }

    @Override
    public void send(Notification notification, String address) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(address);
        message.setSubject(NotificationText.subject(notification));
        message.setText(NotificationText.body(notification));
        mail.send(message);
    }
}
