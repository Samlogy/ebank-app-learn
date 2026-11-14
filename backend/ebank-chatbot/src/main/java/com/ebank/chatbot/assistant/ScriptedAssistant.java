package com.ebank.chatbot.assistant;

import com.ebank.chatbot.assistant.AssistantTools.Result;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Used when no Anthropic API key is configured: keyword rules on top of the same tools, so dev and
 * tests are deterministic and free. It only understands English and French keywords.
 */
class ScriptedAssistant implements Assistant {

    private static final Pattern ADVISOR = Pattern.compile("\\b(advisor|adviser|human|person|agent|conseiller|humain)\\b");
    private static final Pattern BALANCE = Pattern.compile("\\b(balance|solde|how much)\\b");
    private static final Pattern HISTORY = Pattern.compile("\\b(transactions?|transfers?|history|virements?|historique|payments?)\\b");
    private static final Pattern ACCOUNTS = Pattern.compile("\\b(accounts?|comptes?)\\b");

    static final String HELP = "I can tell you your balances, list your accounts or show your recent transfers. "
            + "Type \"advisor\" to talk to a person.";

    private final AssistantTools tools;
    private final JsonMapper json;

    ScriptedAssistant(AssistantTools tools, JsonMapper json) {
        this.tools = tools;
        this.json = json;
    }

    @Override
    public Reply reply(String userId, UUID conversationId, List<Turn> history) {
        String last = history.reversed().stream().filter(t -> t.role() == Turn.Role.CUSTOMER).findFirst()
                .map(t -> t.text().toLowerCase(Locale.ROOT)).orElse("");
        AssistantTools.Session session = tools.session(userId, conversationId);

        if (ADVISOR.matcher(last).find()) {
            session.call(AssistantTools.ESCALATE, Map.of("reason", "Customer asked for an advisor"));
            return new Reply("I'm passing you to an advisor, who will take over this conversation.", session.escalationReason());
        }
        if (BALANCE.matcher(last).find()) {
            JsonNode accounts = read(session.call(AssistantTools.LIST_ACCOUNTS, Map.of())).path("accounts");
            if (accounts.isEmpty()) {
                return new Reply("You don't have any account yet.", Optional.empty());
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode account : accounts) {
                JsonNode balance = read(session.call(AssistantTools.GET_BALANCE, Map.of("account_ref", account.path("ref").asString())));
                text.append(text.isEmpty() ? "" : " ")
                        .append("Your ").append(account.path("type").asString().toLowerCase(Locale.ROOT))
                        .append(" account ending ").append(account.path("ending").asString())
                        .append(" holds ").append(balance.path("balance").asString()).append(' ')
                        .append(balance.path("currency").asString()).append('.');
            }
            return new Reply(text.toString(), Optional.empty());
        }
        if (HISTORY.matcher(last).find()) {
            JsonNode items = read(session.call(AssistantTools.RECENT_TRANSACTIONS, Map.of("account_ref", "all", "limit", 5))).path("transactions");
            if (items.isEmpty()) {
                return new Reply("You have no transfers yet.", Optional.empty());
            }
            StringBuilder text = new StringBuilder("Your latest transfers:");
            for (JsonNode t : items) {
                text.append("\n- ").append(t.path("date").asString()).append(": ")
                        .append(switch (t.path("direction").asString()) {
                            case "IN" -> "received ";
                            case "OUT" -> "sent ";
                            default -> "moved ";
                        })
                        .append(t.path("amount").asString()).append(' ').append(t.path("currency").asString())
                        .append(" (").append(t.path("counterparty").asString()).append(")")
                        .append("CANCELLED".equals(t.path("status").asString()) ? ", refused" : "");
            }
            return new Reply(text.toString(), Optional.empty());
        }
        if (ACCOUNTS.matcher(last).find()) {
            JsonNode accounts = read(session.call(AssistantTools.LIST_ACCOUNTS, Map.of())).path("accounts");
            StringBuilder text = new StringBuilder("You have ").append(accounts.size()).append(" account(s):");
            for (JsonNode a : accounts) {
                text.append("\n- ").append(a.path("type").asString()).append(' ').append(a.path("currency").asString())
                        .append(" ending ").append(a.path("ending").asString()).append(" (").append(a.path("status").asString()).append(')');
            }
            return new Reply(text.toString(), Optional.empty());
        }
        return new Reply(HELP, Optional.empty());
    }

    @Override
    public String summarize(List<Turn> history) {
        List<String> asked = history.stream().filter(t -> t.role() == Turn.Role.CUSTOMER).map(Turn::text).toList();
        if (asked.isEmpty()) {
            return "The customer asked for an advisor without writing anything else.";
        }
        String last = asked.getLast();
        return "Customer's last message: \"" + (last.length() > 200 ? last.substring(0, 200) + "…" : last) + "\"";
    }

    private JsonNode read(Result result) {
        return json.readTree(result.content());
    }
}
