package com.ebank.chatbot.conversation;

/** {@code BOT → WAITING_ADVISOR → WITH_ADVISOR → RESOLVED}. The customer can go back to BOT. */
public enum ConversationState {
    BOT, WAITING_ADVISOR, WITH_ADVISOR, RESOLVED
}
