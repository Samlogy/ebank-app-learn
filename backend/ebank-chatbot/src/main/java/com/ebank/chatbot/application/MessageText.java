package com.ebank.chatbot.application;

import com.ebank.common.error.ApiException;

/** Message text is stored and shown as plain text; clients render it escaped. */
final class MessageText {

    static final int MAX_LENGTH = 2000;

    private MessageText() {}

    static String clean(String text) {
        String cleaned = text == null ? "" : text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
        if (cleaned.isEmpty()) {
            throw ApiException.unprocessable("EMPTY_MESSAGE", "The message is empty.");
        }
        if (cleaned.length() > MAX_LENGTH) {
            throw ApiException.unprocessable("MESSAGE_TOO_LONG", "A message can hold at most " + MAX_LENGTH + " characters.");
        }
        return cleaned;
    }
}
