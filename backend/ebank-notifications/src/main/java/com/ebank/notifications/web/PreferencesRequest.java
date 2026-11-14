package com.ebank.notifications.web;

import jakarta.validation.constraints.NotNull;

/** Which channels to use while the user is offline. Both fields are required, so nothing is changed by omission. */
record PreferencesRequest(@NotNull Boolean emailEnabled, @NotNull Boolean smsEnabled) {}
