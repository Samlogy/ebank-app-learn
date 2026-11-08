package com.ebank.accounts.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebank.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountTest {

    private static Account inStatus(AccountStatus status) {
        Account account = new Account("EB00", AccountType.CURRENT, "user", "EUR");
        switch (status) {
            case CREATED -> {}
            case ACTIVE -> account.activate();
            case DISABLED -> {
                account.activate();
                account.disable();
            }
            case CLOSED -> {
                account.activate();
                account.disable();
                account.close();
            }
        }
        return account;
    }

    private static void apply(Account account, AccountStatus target) {
        switch (target) {
            case ACTIVE -> account.activate();
            case DISABLED -> account.disable();
            case CLOSED -> account.close();
            case CREATED -> throw new IllegalArgumentException();
        }
    }

    @ParameterizedTest
    @CsvSource({"CREATED,ACTIVE", "ACTIVE,DISABLED", "DISABLED,ACTIVE", "DISABLED,CLOSED"})
    void allowsTheDocumentedTransitions(AccountStatus from, AccountStatus to) {
        Account account = inStatus(from);

        apply(account, to);

        assertThat(account.getStatus()).isEqualTo(to);
    }

    @ParameterizedTest
    @CsvSource({
        "CREATED,DISABLED", "CREATED,CLOSED",
        "ACTIVE,ACTIVE", "ACTIVE,CLOSED",
        "DISABLED,DISABLED",
        "CLOSED,ACTIVE", "CLOSED,DISABLED", "CLOSED,CLOSED"
    })
    void rejectsEveryOtherTransition(AccountStatus from, AccountStatus to) {
        Account account = inStatus(from);

        assertThatThrownBy(() -> apply(account, to))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("INVALID_ACCOUNT_STATUS"));
        assertThat(account.getStatus()).isEqualTo(from);
    }

    @Test
    void cryptoAccountNeedsCryptoCurrency() {
        assertThatThrownBy(() -> new Account("EB00", AccountType.CRYPTO, "user", "EUR"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new Account("EB00", AccountType.SAVING, "user", "BTC"))
                .isInstanceOf(ApiException.class);
        assertThat(new Account("EB00", AccountType.CRYPTO, "user", "BTC").getCurrency()).isEqualTo("BTC");
    }

    @Test
    void typeCannotChangeOnClosedAccountOrBreakCurrencyRule() {
        assertThatThrownBy(() -> inStatus(AccountStatus.CLOSED).changeType(AccountType.SAVING))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inStatus(AccountStatus.ACTIVE).changeType(AccountType.CRYPTO))
                .isInstanceOf(ApiException.class);
    }
}
