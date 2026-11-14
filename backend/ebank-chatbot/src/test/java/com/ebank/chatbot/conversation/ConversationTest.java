package com.ebank.chatbot.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ebank.common.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConversationTest {

    private static final Instant T0 = Instant.parse("2026-11-14T10:00:00Z");

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    @Test
    void fullPathFromBotToResolved() {
        Conversation c = new Conversation("alice", T0);
        assertThat(c.getState()).isEqualTo(ConversationState.BOT);

        c.handoff(HandoffReason.USER_REQUEST, T0);
        assertThat(c.getState()).isEqualTo(ConversationState.WAITING_ADVISOR);
        assertThat(c.getHandoffReason()).isEqualTo(HandoffReason.USER_REQUEST);

        c.assign("adv", T0.plusSeconds(90));
        assertThat(c.isAssignedTo("adv")).isTrue();
        assertThat(c.isAssignedTo("other")).isFalse();
        assertThat(c.queueWait()).contains(Duration.ofSeconds(90));

        c.resolve(T0.plusSeconds(300));
        assertThat(c.getState()).isEqualTo(ConversationState.RESOLVED);
        assertThat(c.acceptsCustomerMessages()).isFalse();
    }

    @Test
    void secondAdvisorCannotTakeAnAssignedConversation() {
        Conversation c = new Conversation("alice", T0);
        c.handoff(HandoffReason.LLM_ESCALATION, T0);
        c.assign("adv1", T0);

        assertCode(() -> c.assign("adv2", T0), "ALREADY_ASSIGNED");
        assertThat(c.getAdvisorId()).isEqualTo("adv1");
    }

    @Test
    void customerCanGoBackToTheBotFromTheQueueOrAfterResolution() {
        Conversation c = new Conversation("alice", T0);
        c.handoff(HandoffReason.USER_REQUEST, T0);
        c.backToBot();
        assertThat(c.getState()).isEqualTo(ConversationState.BOT);
        assertThat(c.getHandoffReason()).isNull();

        c.resolve(T0);
        c.backToBot();
        assertThat(c.getState()).isEqualTo(ConversationState.BOT);
    }

    @Test
    void invalidTransitionsAreRefused() {
        Conversation c = new Conversation("alice", T0);
        assertCode(() -> c.assign("adv", T0), "ALREADY_ASSIGNED");
        assertCode(c::backToBot, "BACK_TO_BOT_NOT_ALLOWED");

        c.handoff(HandoffReason.USER_REQUEST, T0);
        assertCode(() -> c.handoff(HandoffReason.USER_REQUEST, T0), "HANDOFF_NOT_ALLOWED");

        c.assign("adv", T0);
        assertCode(c::backToBot, "BACK_TO_BOT_NOT_ALLOWED");
        c.resolve(T0);
        assertCode(() -> c.resolve(T0), "ALREADY_RESOLVED");
    }

    @Test
    void sequenceNumbersIncrease() {
        Conversation c = new Conversation("alice", T0);
        assertThat(c.nextSeq()).isEqualTo(1);
        assertThat(c.nextSeq()).isEqualTo(2);
        assertThat(c.getLastSeq()).isEqualTo(2);
    }

    @Test
    void readPointerOnlyMovesForward() {
        Participant p = new Participant(java.util.UUID.randomUUID(), "alice", Participant.Role.CUSTOMER, T0);
        assertThat(p.markRead(5)).isTrue();
        assertThat(p.markRead(3)).isFalse();
        assertThat(p.getLastReadSeq()).isEqualTo(5);
    }

    @Test
    void deletedMessageLosesItsContent() {
        ChatMessage m = new ChatMessage(java.util.UUID.randomUUID(), 1, SenderType.CUSTOMER, "alice", MessageType.TEXT, "secret", "c1", T0);
        m.delete(T0);
        assertThat(m.isDeleted()).isTrue();
        assertThat(m.getContent()).isEmpty();
    }
}
