package com.ebank.auth.api;

import java.time.Instant;
import org.springframework.security.core.Authentication;

/**
 * Validates an access token outside of an HTTP request, such as in a STOMP CONNECT frame. The
 * same checks and role mapping apply as for the REST API.
 */
public interface TokenAuthenticator {

    /** Throws {@link org.springframework.security.core.AuthenticationException} if the token is not valid. */
    AuthenticatedToken authenticate(String bearerToken);

    /** The caller's name is the JWT {@code sub}. */
    record AuthenticatedToken(Authentication authentication, Instant expiresAt) {

        public String userId() {
            return authentication.getName();
        }
    }
}
