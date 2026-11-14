package com.ebank.notifications.infrastructure.keycloak;

import com.ebank.notifications.application.UserDirectory;
import com.ebank.notifications.infrastructure.NotificationsProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Reads email and phone from the Keycloak Admin API with the {@code ebank-notifications}
 * service account, which only has the {@code view-users} role. Nothing is cached or logged.
 */
@Component
class KeycloakUserDirectory implements UserDirectory {

    private static final Logger log = LoggerFactory.getLogger(KeycloakUserDirectory.class);
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofSeconds(30);

    private final NotificationsProperties.Keycloak properties;
    private final RestClient http;
    private final ReentrantLock tokenLock = new ReentrantLock();    // not synchronized: virtual threads
    private volatile String token;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    KeycloakUserDirectory(NotificationsProperties properties, RestClient.Builder builder) {
        this.properties = properties.keycloak();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        requests.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.clone().requestFactory(requests).build();
        if (!this.properties.configured()) {
            log.warn("ebank.notifications.keycloak is not configured: offline users get no email or SMS");
        }
    }

    record KeycloakUser(String email, Boolean emailVerified, Map<String, List<String>> attributes) {}

    record TokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) {}

    @Override
    public Optional<UserContact> find(String userId) {
        if (!properties.configured()) {
            return Optional.empty();
        }
        KeycloakUser user;
        try {
            user = http.get()
                    .uri(properties.serverUrl() + "/admin/realms/{realm}/users/{id}", properties.realm(), userId)
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .retrieve()
                    .body(KeycloakUser.class);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                tokenExpiresAt = Instant.EPOCH;    // fetch a new token on the retry
            }
            throw ex;
        }
        if (user == null) {
            return Optional.empty();
        }
        String email = Boolean.TRUE.equals(user.emailVerified()) ? user.email() : null;
        return Optional.of(new UserContact(email, firstAttribute(user, "phone")));
    }

    private static String firstAttribute(KeycloakUser user, String name) {
        if (user.attributes() == null) {
            return null;
        }
        List<String> values = user.attributes().get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private String accessToken() {
        if (Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        tokenLock.lock();
        try {
            if (Instant.now().isBefore(tokenExpiresAt)) {
                return token;
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", properties.clientId());
            form.add("client_secret", properties.clientSecret());
            TokenResponse response = http.post()
                    .uri(properties.serverUrl() + "/realms/{realm}/protocol/openid-connect/token", properties.realm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null) {
                throw new IllegalStateException("Keycloak returned no access token");
            }
            token = response.accessToken();
            tokenExpiresAt = Instant.now().plusSeconds(response.expiresIn()).minus(TOKEN_SAFETY_MARGIN);
            return token;
        } finally {
            tokenLock.unlock();
        }
    }
}
