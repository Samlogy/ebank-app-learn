package com.ebank.auth.api;

import com.ebank.common.error.ApiException;

/** The authenticated caller of the current request. */
public interface CurrentUser {

    /** The JWT {@code sub}. Throws a 401 if nobody is authenticated. */
    String userId();

    boolean isAdmin();

    /** A Keycloak realm role, such as {@code ADVISOR}. */
    boolean hasRole(String role);

    default void requireRole(String role) {
        if (!hasRole(role)) {
            throw ApiException.forbidden("This operation requires the " + role + " role.");
        }
    }

    default void requireAdmin() {
        if (!isAdmin()) {
            throw ApiException.forbidden("This operation requires the ADMIN role.");
        }
    }
}
