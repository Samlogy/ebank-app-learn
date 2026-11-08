package com.ebank.common.money;

import com.ebank.common.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount in a currency. The amount is always stored at the currency's scale, so 10.5 EUR
 * and 10.50 EUR are equal. An amount with more decimals than the currency allows is rejected,
 * never rounded.
 */
public record Money(BigDecimal amount, String currency) {

    private static final int MAX_INTEGER_DIGITS = 18;

    public Money {
        if (amount == null) {
            throw invalid("An amount is required.");
        }
        if (!Currencies.isSupported(currency)) {
            throw ApiException.unprocessable("UNSUPPORTED_CURRENCY", "This currency is not supported.");
        }
        // Checked before any scaling, because a value like 1E+1000000 would allocate a huge number.
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw invalid("The amount is too large.");
        }
        int maxScale = Currencies.maxScale(currency);
        if (amount.stripTrailingZeros().scale() > maxScale) {
            throw invalid("The amount has too many decimals for " + currency + ".");
        }
        amount = amount.setScale(maxScale, RoundingMode.UNNECESSARY);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isLessThan(Money other) {
        return amount.compareTo(other.amount) < 0;
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("INVALID_AMOUNT", message);
    }
}
