package com.ebank.notifications.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param email sender of fallback emails
 * @param keycloak service account used to read users' email and phone. Fallback is skipped
 *     while {@code client-secret} is empty.
 */
@ConfigurationProperties("ebank.notifications")
public record NotificationsProperties(@DefaultValue Email email, @DefaultValue Keycloak keycloak) {

    public record Email(@DefaultValue("EBANK <noreply@ebank.local>") String from) {}

    public record Keycloak(
            String serverUrl,
            @DefaultValue("ebank") String realm,
            @DefaultValue("ebank-notifications") String clientId,
            String clientSecret) {

        public boolean configured() {
            return serverUrl != null && !serverUrl.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
