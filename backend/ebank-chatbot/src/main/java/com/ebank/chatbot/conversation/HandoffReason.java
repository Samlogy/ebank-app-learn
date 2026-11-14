package com.ebank.chatbot.conversation;

public enum HandoffReason {
    /** The customer asked for a human. */
    USER_REQUEST,
    /** The assistant called escalate_to_advisor. */
    LLM_ESCALATION
}
