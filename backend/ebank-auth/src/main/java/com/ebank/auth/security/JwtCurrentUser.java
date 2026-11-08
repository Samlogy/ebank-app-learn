package com.ebank.auth.security;

import com.ebank.auth.api.CurrentUser;
import com.ebank.common.error.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** The caller as proven by the validated access token. */
@Component
class JwtCurrentUser implements CurrentUser {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final SecurityRejections rejections;

    JwtCurrentUser(SecurityRejections rejections) {
        this.rejections = rejections;
    }

    @Override
    public String userId() {
        return token().getToken().getSubject();
    }

    @Override
    public boolean isAdmin() {
        return token().getAuthorities().stream().anyMatch(authority -> ADMIN_AUTHORITY.equals(authority.getAuthority()));
    }

    /** Overridden because these 403s come from business code, not from the security filters. */
    @Override
    public void requireAdmin() {
        if (!isAdmin()) {
            rejections.countForbidden();
            throw ApiException.forbidden("This operation requires the ADMIN role.");
        }
    }

    private static JwtAuthenticationToken token() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return token;
        }
        throw ApiException.unauthorized("A valid access token is required.");
    }
}
