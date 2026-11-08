package com.ebank.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebank.common.error.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void equalAmountsAreEqualWhateverTheirScale() {
        assertThat(new Money(new BigDecimal("10.5"), "EUR")).isEqualTo(new Money(new BigDecimal("10.500"), "EUR"));
    }

    @ParameterizedTest
    @CsvSource({"10.555,EUR", "1.5,JPY", "0.000000001,BTC"})
    void rejectsMoreDecimalsThanTheCurrencyAllows(String amount, String currency) {
        assertCode(amount, currency, "INVALID_AMOUNT");
    }

    @ParameterizedTest
    @CsvSource({"10.55,EUR", "150,JPY", "0.00000001,BTC"})
    void acceptsDecimalsTheCurrencyAllows(String amount, String currency) {
        assertThat(new Money(new BigDecimal(amount), currency).amount()).isEqualByComparingTo(amount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E+1000000", "1000000000000000000"})
    void rejectsAmountsTooLargeForStorage(String amount) {
        assertCode(amount, "EUR", "INVALID_AMOUNT");
    }

    @Test
    void rejectsExtremelySmallExponentWithoutAllocating() {
        assertCode("1E-1000000", "EUR", "INVALID_AMOUNT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABC", "XAU", "eur", ""})
    void rejectsUnsupportedCurrencies(String currency) {
        assertCode("1", currency, "UNSUPPORTED_CURRENCY");
    }

    private static void assertCode(String amount, String currency, String code) {
        assertThatThrownBy(() -> new Money(new BigDecimal(amount), currency))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }
}
