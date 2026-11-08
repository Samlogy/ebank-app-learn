package com.ebank.common.money;

import java.util.Currency;
import java.util.Set;

/** The currencies the bank supports, and how many decimals each one allows. */
public final class Currencies {

    private static final Set<String> CRYPTO = Set.of("BTC", "ETH");
    private static final int CRYPTO_MAX_SCALE = 8;

    private Currencies() {}

    public static boolean isCrypto(String code) {
        return CRYPTO.contains(code);
    }

    public static boolean isSupported(String code) {
        return code != null && (isCrypto(code) || fiatScale(code) >= 0);
    }

    /** Requires a supported currency. */
    public static int maxScale(String code) {
        return isCrypto(code) ? CRYPTO_MAX_SCALE : fiatScale(code);
    }

    /** Returns -1 for unknown codes and for non-monetary ISO codes such as XAU. */
    private static int fiatScale(String code) {
        try {
            return Currency.getInstance(code).getDefaultFractionDigits();
        } catch (IllegalArgumentException ex) {
            return -1;
        }
    }
}
