package com.ebank.chatbot.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiMaskerTest {

    @Test
    void masksIdentifiersButKeepsTheQuestion() {
        String masked = PiiMasker.mask(
                "Hi, I'm jane.doe@example.com. Why did EB0907371801311858562479 get charged? Card 4111 1111 1111 1111, call +33 6 12 34 56 78");

        assertThat(masked)
                .contains("[email]", "[account number]", "[card number]", "[phone]", "Why did", "get charged?")
                .doesNotContain("jane.doe", "EB0907371801311858562479", "4111", "12 34 56");
    }

    @Test
    void ibanWithSpacesIsMasked() {
        assertThat(PiiMasker.mask("send to FR76 3000 6000 0112 3456 7890 189")).isEqualTo("send to [account number]");
    }

    @Test
    void ordinaryNumbersAndAmountsAreKept() {
        assertThat(PiiMasker.mask("Did my 30.50 EUR transfer on 2026-11-14 go through? It was 2 days ago."))
                .isEqualTo("Did my 30.50 EUR transfer on 2026-11-14 go through? It was 2 days ago.");
    }
}
