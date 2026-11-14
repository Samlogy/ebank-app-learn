package com.ebank.chatbot.assistant;

import com.ebank.accounts.api.AccountsApi;
import com.ebank.accounts.api.OwnedAccount;
import com.ebank.chatbot.audit.AuditLog;
import com.ebank.transactions.api.TransactionsApi;
import com.ebank.transactions.api.TransactionsApi.TransferSummary;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The assistant's tools: read-only, scoped to one customer. The one exception,
 * {@code escalate_to_advisor}, only changes the conversation state.
 *
 * <p>The customer is fixed when the {@link Session} is created, from their token. Nothing the model
 * sends can change it. Accounts are shown to the model as {@code account_1}, {@code account_2}...
 * with their last 4 characters, never as full account numbers.
 */
@Component
public class AssistantTools {

    public static final String LIST_ACCOUNTS = "list_accounts";
    public static final String GET_BALANCE = "get_balance";
    public static final String RECENT_TRANSACTIONS = "list_recent_transactions";
    public static final String ESCALATE = "escalate_to_advisor";

    static final int MAX_TRANSACTIONS = 10;
    private static final Logger log = LoggerFactory.getLogger(AssistantTools.class);

    private final AccountsApi accounts;
    private final TransactionsApi transactions;
    private final AuditLog audit;
    private final JsonMapper json;
    private final MeterRegistry meters;

    AssistantTools(AccountsApi accounts, TransactionsApi transactions, AuditLog audit, JsonMapper json, MeterRegistry meters) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.audit = audit;
        this.json = json;
        this.meters = meters;
    }

    /** The JSON a tool returns to the model, and whether it is an error. */
    public record Result(String content, boolean error) {}

    /** One reply's worth of tool calls for one customer. */
    public final class Session {
        private final String userId;
        private final UUID conversationId;
        private Map<String, OwnedAccount> byRef;
        private String escalationReason;

        private Session(String userId, UUID conversationId) {
            this.userId = userId;
            this.conversationId = conversationId;
        }

        public Optional<String> escalationReason() {
            return Optional.ofNullable(escalationReason);
        }

        public Result call(String tool, Map<String, Object> input) {
            Result result;
            try {
                result = switch (tool) {
                    case LIST_ACCOUNTS -> listAccounts();
                    case GET_BALANCE -> balance(string(input, "account_ref"));
                    case RECENT_TRANSACTIONS -> recent(string(input, "account_ref"), number(input, "limit"));
                    case ESCALATE -> escalate(string(input, "reason"));
                    default -> error("Unknown tool.");
                };
            } catch (RuntimeException ex) {
                log.warn("Assistant tool {} failed: {}", tool, ex.getClass().getSimpleName());
                result = error("The tool failed. Tell the customer you cannot check this right now.");
            }
            String outcome = result.error() ? "error" : "ok";
            audit.record(userId, conversationId, AuditLog.Action.TOOL_CALL, tool + ":" + outcome);
            Counter.builder("ebank.chatbot.tool.calls").description("Assistant tool calls")
                    .tag("tool", knownTool(tool)).tag("outcome", outcome).register(meters).increment();
            return result;
        }

        private Map<String, OwnedAccount> accounts() {
            if (byRef == null) {
                byRef = new LinkedHashMap<>();
                List<OwnedAccount> owned = accounts.accountsOwnedBy(userId);
                for (int i = 0; i < owned.size(); i++) {
                    byRef.put("account_" + (i + 1), owned.get(i));
                }
            }
            return byRef;
        }

        private Result listAccounts() {
            List<Map<String, Object>> list = new ArrayList<>();
            accounts().forEach((ref, a) -> list.add(Map.of(
                    "ref", ref, "type", a.type(), "currency", a.currency(), "status", a.status(), "ending", ending(a.accountId()))));
            return ok(Map.of("accounts", list));
        }

        private Result balance(String ref) {
            OwnedAccount account = accounts().get(ref);
            if (account == null) {
                return error("Unknown account_ref. Call list_accounts to get the customer's references.");
            }
            return transactions.balanceOf(userId, account.accountId())
                    .map(money -> ok(Map.of("account_ref", ref, "balance", money.amount().toPlainString(), "currency", money.currency())))
                    .orElseGet(() -> error("Unknown account_ref."));
        }

        private Result recent(String ref, Integer limit) {
            String accountId = null;
            if (ref != null && !ref.isBlank() && !"all".equals(ref)) {
                OwnedAccount account = accounts().get(ref);
                if (account == null) {
                    return error("Unknown account_ref. Use one from list_accounts, or \"all\".");
                }
                accountId = account.accountId();
            }
            int size = limit == null ? 5 : Math.clamp(limit, 1, MAX_TRANSACTIONS);
            Map<String, String> refOf = new LinkedHashMap<>();
            accounts().forEach((r, a) -> refOf.put(a.accountId(), r));
            List<Map<String, Object>> items = new ArrayList<>();
            for (TransferSummary t : transactions.recentTransfers(userId, accountId, size)) {
                boolean out = refOf.containsKey(t.senderAccountId());
                boolean in = refOf.containsKey(t.receiverAccountId());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", t.createdAt().atOffset(ZoneOffset.UTC).toLocalDate().toString());
                item.put("direction", out && in ? "INTERNAL" : out ? "OUT" : "IN");
                item.put("amount", t.money().amount().toPlainString());
                item.put("currency", t.money().currency());
                item.put("status", t.status());
                item.put("account", out ? refOf.get(t.senderAccountId()) : refOf.get(t.receiverAccountId()));
                String other = out ? t.receiverAccountId() : t.senderAccountId();
                item.put("counterparty", refOf.containsKey(other) ? refOf.get(other)
                        : other.startsWith("EXTERNAL-") ? "outside EBANK" : "account ending " + ending(other));
                items.add(item);
            }
            return ok(Map.of("transactions", items));
        }

        private Result escalate(String reason) {
            String text = reason == null || reason.isBlank() ? "Customer needs an advisor" : reason.strip();
            escalationReason = text.length() > 200 ? text.substring(0, 200) : text;
            return ok(Map.of("escalated", true, "next", "Tell the customer an advisor will take over this conversation."));
        }
    }

    public Session session(String userId, UUID conversationId) {
        return new Session(userId, conversationId);
    }

    /** Descriptions are written for the model; schemas are strict and minimal. */
    public record Definition(String name, String description, Map<String, Map<String, Object>> properties, List<String> required) {}

    public List<Definition> definitions() {
        Map<String, Object> ref = Map.of("type", "string",
                "description", "An account reference from list_accounts, such as account_1.");
        return List.of(
                new Definition(LIST_ACCOUNTS,
                        "List the signed-in customer's accounts: reference, type, currency, status and the last 4 characters "
                                + "of the account number. Call this first to get account references.",
                        Map.of(), List.of()),
                new Definition(GET_BALANCE, "Get the current balance of one of the customer's accounts.",
                        Map.of("account_ref", ref), List.of("account_ref")),
                new Definition(RECENT_TRANSACTIONS,
                        "List the customer's most recent transfers, newest first. Refused transfers have status CANCELLED.",
                        Map.of("account_ref", Map.of("type", "string",
                                        "description", "An account reference from list_accounts, or \"all\" for every account."),
                                "limit", Map.of("type", "integer", "description", "How many transfers, 1 to 10.")),
                        List.of("account_ref", "limit")),
                new Definition(ESCALATE,
                        "Hand the conversation to a human advisor. Use it when the customer asks for a person, or needs "
                                + "something the other tools cannot do: disputes, fraud, blocking a card, loans, complaints, "
                                + "changing anything. Give a short reason without personal details.",
                        Map.of("reason", Map.of("type", "string", "description", "Why an advisor is needed, in one short sentence.")),
                        List.of("reason")));
    }

    private Result ok(Object value) {
        return new Result(json.writeValueAsString(value), false);
    }

    private Result error(String message) {
        return new Result(json.writeValueAsString(Map.of("error", message)), true);
    }

    private static String string(Map<String, Object> input, String key) {
        return input != null && input.get(key) instanceof String s ? s : null;
    }

    private static Integer number(Map<String, Object> input, String key) {
        return input != null && input.get(key) instanceof Number n ? n.intValue() : null;
    }

    static String ending(String accountId) {
        return accountId == null || accountId.length() <= 4 ? "****" : accountId.substring(accountId.length() - 4);
    }

    private static String knownTool(String tool) {
        return switch (tool) {
            case LIST_ACCOUNTS, GET_BALANCE, RECENT_TRANSACTIONS, ESCALATE -> tool;
            default -> "unknown";
        };
    }
}
