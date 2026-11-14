package com.ebank.notifications.application;

import java.util.Optional;

/** Contact details live in Keycloak, not in this application's database. */
public interface UserDirectory {

    /** Empty when the user does not exist or the directory is not configured. */
    Optional<UserContact> find(String userId);

    /**
     * @param email null when unknown or not verified, so mail never goes to an unproven address
     * @param phone null when not set
     */
    record UserContact(String email, String phone) {}
}
