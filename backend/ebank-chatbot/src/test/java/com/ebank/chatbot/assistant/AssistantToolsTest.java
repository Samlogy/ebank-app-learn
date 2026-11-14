package com.ebank.chatbot.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ebank.accounts.api.AccountsApi;
import com.ebank.accounts.api.OwnedAccount;
import com.ebank.chatbot.audit.AuditLog;
import com.ebank.common.money.Money;
import com.ebank.transactions.api.TransactionsApi;
import com.ebank.transactions.api.TransactionsApi.TransferSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AssistantToolsTest {

    private static final String MAIN = "EB0907371801311858562479";
    private static final String SAVINGS = "EB4811658276728332327915";

    @Mock AccountsApi accounts;
    @Mock TransactionsApi transactions;
    @Mock AuditLog audit;
    private final JsonMapper json = JsonMapper.builder().build();
    private AssistantTools.Session session;
    private final UUID conversation = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AssistantTools tools = new AssistantTools(accounts, transactions, audit, json, new SimpleMeterRegistry());
        session = tools.session("alice", conversation);
        lenient().when(accounts.accountsOwnedBy("alice")).thenReturn(List.of(
                new OwnedAccount(MAIN, "CURRENT", "EUR", "ACTIVE", Instant.EPOCH),
                new OwnedAccount(SAVINGS, "SAVING", "EUR", "ACTIVE", Instant.EPOCH.plusSeconds(1))));
    }

    private JsonNode call(String tool, Map<String, Object> input) {
        AssistantTools.Result result = session.call(tool, input);
        return json.readTree(result.content());
    }

    @Test
    void accountsAreShownByReferenceAndLastFourCharactersOnly() {
        AssistantTools.Result result = session.call(AssistantTools.LIST_ACCOUNTS, Map.of());

        assertThat(result.error()).isFalse();
        assertThat(result.content()).doesNotContain(MAIN).doesNotContain(SAVINGS);
        JsonNode first = json.readTree(result.content()).path("accounts").get(0);
        assertThat(first.path("ref").asString()).isEqualTo("account_1");
        assertThat(first.path("ending").asString()).isEqualTo("2479");
        verify(audit).record("alice", conversation, AuditLog.Action.TOOL_CALL, "list_accounts:ok");
    }

    @Test
    void balanceUsesTheSessionUserNotAnythingFromTheModel() {
        when(transactions.balanceOf("alice", SAVINGS)).thenReturn(Optional.of(new Money(new BigDecimal("12.5"), "EUR")));

        JsonNode balance = call(AssistantTools.GET_BALANCE, Map.of("account_ref", "account_2", "user_id", "mallory"));

        assertThat(balance.path("balance").asString()).isEqualTo("12.50");
        verify(transactions).balanceOf(eq("alice"), eq(SAVINGS));
    }

    @Test
    void unknownReferencesAndToolsAreErrors() {
        assertThat(session.call(AssistantTools.GET_BALANCE, Map.of("account_ref", MAIN)).error()).isTrue();
        assertThat(session.call(AssistantTools.GET_BALANCE, Map.of()).error()).isTrue();
        assertThat(session.call("transfer_money", Map.of("amount", 100)).error()).isTrue();
        verify(transactions, never()).balanceOf(anyString(), anyString());
    }

    @Test
    void transactionsAreMaskedAndLimited() {
        when(transactions.recentTransfers(eq("alice"), isNull(), anyInt())).thenReturn(List.of(
                new TransferSummary("TXN1", MAIN, "EB0000000000000000009999", new Money(new BigDecimal("30.5"), "EUR"), "DONE", Instant.EPOCH),
                new TransferSummary("TXN2", "EXTERNAL-EUR", MAIN, new Money(BigDecimal.TEN, "EUR"), "DONE", Instant.EPOCH),
                new TransferSummary("TXN3", MAIN, SAVINGS, new Money(BigDecimal.ONE, "EUR"), "DONE", Instant.EPOCH)));

        AssistantTools.Result result = session.call(AssistantTools.RECENT_TRANSACTIONS, Map.of("account_ref", "all", "limit", 500));

        verify(transactions).recentTransfers("alice", null, AssistantTools.MAX_TRANSACTIONS);
        assertThat(result.content()).doesNotContain("EB0000000000000000009999").doesNotContain(MAIN);
        JsonNode items = json.readTree(result.content()).path("transactions");
        assertThat(items.get(0).path("direction").asString()).isEqualTo("OUT");
        assertThat(items.get(0).path("counterparty").asString()).isEqualTo("account ending 9999");
        assertThat(items.get(1).path("counterparty").asString()).isEqualTo("outside EBANK");
        assertThat(items.get(2).path("direction").asString()).isEqualTo("INTERNAL");
        assertThat(items.get(2).path("counterparty").asString()).isEqualTo("account_2");
    }

    @Test
    void escalationOnlyRecordsAReason() {
        assertThat(session.escalationReason()).isEmpty();

        call(AssistantTools.ESCALATE, Map.of("reason", "Card stolen"));

        assertThat(session.escalationReason()).contains("Card stolen");
        verify(transactions, never()).recentTransfers(any(), any(), anyInt());
    }
}
