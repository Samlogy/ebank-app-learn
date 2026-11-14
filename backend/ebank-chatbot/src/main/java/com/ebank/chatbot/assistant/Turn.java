package com.ebank.chatbot.assistant;

/** One message of the history given to the assistant. Text is already masked. */
public record Turn(Role role, String text) {

    public enum Role { CUSTOMER, ASSISTANT }
}
