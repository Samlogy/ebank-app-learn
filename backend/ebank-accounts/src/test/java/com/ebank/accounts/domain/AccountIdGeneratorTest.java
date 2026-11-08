package com.ebank.accounts.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import org.junit.jupiter.api.RepeatedTest;

class AccountIdGeneratorTest {

    @RepeatedTest(200)
    void generatesValidMod97CheckDigits() {
        String id = AccountIdGenerator.next();

        assertThat(id).matches("EB\\d{22}");
        // IBAN validation: move the first 4 characters to the end, letters to numbers, mod 97 must be 1.
        String numeric = id.substring(4) + "1411" + id.substring(2, 4);
        assertThat(new BigInteger(numeric).mod(BigInteger.valueOf(97)).intValue()).isEqualTo(1);
    }
}
