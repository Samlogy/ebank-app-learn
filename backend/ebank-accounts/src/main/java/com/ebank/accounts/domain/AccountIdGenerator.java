package com.ebank.accounts.domain;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Generates IBAN-like business IDs: "EB", two check digits (ISO 7064 mod 97, as in a real IBAN),
 * then 20 random digits. "EB" is not a real country code, so these can never be mistaken for a
 * real IBAN. Random digits also stop anyone guessing a neighbouring account.
 */
public final class AccountIdGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DIGITS = 20;
    /** "EB" as IBAN digits: E=14, B=11. */
    private static final String COUNTRY_DIGITS = "1411";
    private static final BigInteger MOD = BigInteger.valueOf(97);

    private AccountIdGenerator() {}

    public static String next() {
        StringBuilder digits = new StringBuilder(DIGITS);
        for (int i = 0; i < DIGITS; i++) {
            digits.append(RANDOM.nextInt(10));
        }
        int remainder = new BigInteger(digits + COUNTRY_DIGITS + "00").mod(MOD).intValue();
        return "EB%02d%s".formatted(98 - remainder, digits);
    }
}
