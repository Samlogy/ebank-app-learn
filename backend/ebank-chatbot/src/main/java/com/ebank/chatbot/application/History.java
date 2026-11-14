package com.ebank.chatbot.application;

import com.ebank.chatbot.assistant.PiiMasker;
import com.ebank.chatbot.assistant.Turn;
import com.ebank.chatbot.conversation.ChatMessage;
import com.ebank.chatbot.conversation.MessageType;
import com.ebank.chatbot.conversation.SenderType;
import java.util.List;

/** Turns stored messages into assistant context: text only, masked, without system or deleted messages. */
final class History {

    private History() {}

    static List<Turn> of(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m.getType() == MessageType.TEXT && !m.isDeleted())
                .filter(m -> m.getSenderType() == SenderType.CUSTOMER || m.getSenderType() == SenderType.ASSISTANT)
                .map(m -> new Turn(
                        m.getSenderType() == SenderType.CUSTOMER ? Turn.Role.CUSTOMER : Turn.Role.ASSISTANT,
                        m.getSenderType() == SenderType.CUSTOMER ? PiiMasker.mask(m.getContent()) : m.getContent()))
                .toList();
    }
}
