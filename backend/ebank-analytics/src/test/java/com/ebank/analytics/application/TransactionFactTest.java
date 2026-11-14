package com.ebank.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebank.analytics.application.TransactionFact.Kind;
import com.ebank.analytics.application.TransactionFact.Status;
import com.ebank.transactions.api.TransactionCancelled;
import com.ebank.transactions.api.TransactionCompleted;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TransactionFactTest {

    private static TransactionCompleted completed(String sender, String receiver, String amount, String currency) {
        return new TransactionCompleted("TXN1", "A", "B", sender, receiver, new BigDecimal(amount), currency, "rent", Instant.EPOCH);
    }

    @Test
    void amountsAreStoredExactlyInMinorUnits() {
        assertThat(TransactionFact.of(completed("alice", "bob", "30.50000000", "EUR")).amountMinor()).isEqualTo(3050);
        assertThat(TransactionFact.of(completed("alice", "bob", "1000", "JPY")).amountMinor()).isEqualTo(1000);
        assertThat(TransactionFact.of(completed("alice", "bob", "0.00000001", "BTC")).amountMinor()).isEqualTo(1);
        assertThat(TransactionFact.of(completed("alice", "bob", "1", "BTC")).scale()).isEqualTo(8);
    }

    @Test
    void moreDecimalsThanTheCurrencyAllowsIsABug() {
        assertThatThrownBy(() -> TransactionFact.of(completed("alice", "bob", "1.005", "EUR")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void transferBetweenTwoPeopleHasBothAsParticipants() {
        TransactionFact fact = TransactionFact.of(completed("alice", "bob", "1", "EUR"));

        assertThat(fact.participants()).containsExactly("alice", "bob");
        assertThat(fact.internal()).isFalse();
        assertThat(fact.kind()).isEqualTo(Kind.TRANSFER);
        assertThat(fact.status()).isEqualTo(Status.DONE);
    }

    @Test
    void ownAccountsMoveIsInternalWithOneParticipant() {
        TransactionFact fact = TransactionFact.of(completed("alice", "alice", "1", "EUR"));

        assertThat(fact.internal()).isTrue();
        assertThat(fact.participants()).containsExactly("alice");
    }

    @Test
    void depositHasNoSenderUser() {
        TransactionFact fact = TransactionFact.of(completed(null, "bob", "1", "EUR"));

        assertThat(fact.kind()).isEqualTo(Kind.DEPOSIT);
        assertThat(fact.participants()).containsExactly("bob");
        assertThat(fact.internal()).isFalse();
    }

    @Test
    void cancelledTransferIsOnlyVisibleToTheSender() {
        TransactionFact fact = TransactionFact.of(new TransactionCancelled(
                "TXN2", "A", "B", "alice", new BigDecimal("10.00"), "EUR", "INSUFFICIENT_FUNDS", Instant.EPOCH));

        assertThat(fact.status()).isEqualTo(Status.CANCELLED);
        assertThat(fact.participants()).containsExactly("alice");
        assertThat(fact.receiverUserId()).isNull();
        assertThat(fact.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }
}
