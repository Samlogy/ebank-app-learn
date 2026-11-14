package com.ebank.chatbot.assistant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Answers the customer while the conversation is in BOT state. */
public interface Assistant {

    /**
     * @param userId the conversation's customer, from their token. Tools only ever read this user's data.
     */
    Reply reply(String userId, UUID conversationId, List<Turn> history);

    /** A short, factual summary of the request for the advisor taking over. */
    String summarize(List<Turn> history);

    /** @param escalationReason set when the assistant asked for an advisor */
    record Reply(String text, Optional<String> escalationReason) {}
}
