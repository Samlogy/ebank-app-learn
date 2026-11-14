package com.ebank.auth.security;

import com.ebank.auth.api.TokenAuthenticator;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

@Component
class JwtTokenAuthenticator implements TokenAuthenticator {

    private final JwtDecoder decoder;
    private final JwtAuthenticationConverter converter;
    private final SecurityRejections rejections;

    JwtTokenAuthenticator(JwtDecoder decoder, JwtAuthenticationConverter converter, SecurityRejections rejections) {
        this.decoder = decoder;
        this.converter = converter;
        this.rejections = rejections;
    }

    @Override
    public AuthenticatedToken authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            rejections.countUnauthorized();
            throw new BadCredentialsException("A valid access token is required.");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(bearerToken);
        } catch (JwtException ex) {
            // The reason can quote token contents, so it stays out of the message.
            rejections.countUnauthorized();
            throw new BadCredentialsException("A valid access token is required.");
        }
        AbstractAuthenticationToken authentication = converter.convert(jwt);
        return new AuthenticatedToken(authentication, jwt.getExpiresAt());
    }
}
