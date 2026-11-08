package com.ebank.transactions.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebank.common.money.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TransferTest {

    private static final Money TEN = new Money(new BigDecimal("10.00"), "EUR");

    private static Transfer pending() {
        return new Transfer("A", "B", TEN, "rent", "key", "alice");
    }

    @Test
    void startsPendingAndHasOneFinalState() {
        Transfer done = pending();
        assertThat(done.getStatus()).isEqualTo(TransferStatus.PENDING);

        done.complete();

        assertThat(done.getStatus()).isEqualTo(TransferStatus.DONE);
        assertThatThrownBy(() -> done.cancel("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(done::complete).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancelledTransferKeepsItsReasonAndCannotComplete() {
        Transfer cancelled = pending();

        cancelled.cancel("INSUFFICIENT_FUNDS");

        assertThat(cancelled.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThatThrownBy(cancelled::complete).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recognizesTheSameRequestWhateverTheAmountScale() {
        Transfer transfer = pending();

        assertThat(transfer.isSameRequest("A", "B", new Money(new BigDecimal("10"), "EUR"), "rent")).isTrue();
        assertThat(transfer.isSameRequest("A", "C", TEN, "rent")).isFalse();
        assertThat(transfer.isSameRequest("A", "B", new Money(new BigDecimal("11"), "EUR"), "rent")).isFalse();
        assertThat(transfer.isSameRequest("A", "B", TEN, null)).isFalse();
    }
}
