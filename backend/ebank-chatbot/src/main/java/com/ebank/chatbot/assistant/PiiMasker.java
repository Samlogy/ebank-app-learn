package com.ebank.chatbot.assistant;

import java.util.regex.Pattern;

/**
 * Removes identifiers the customer types before their text leaves the bank for the LLM. The
 * assistant never needs them: it refers to accounts as {@code account_1}, {@code account_2}...
 */
public final class PiiMasker {

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    /**
     * IBAN-like account numbers, including EBANK's own (EB + 22 digits): written in one block, or in
     * groups of 4 as printed on statements. Groups must be exactly 4 characters, so words after the
     * number are not swallowed.
     */
    private static final Pattern ACCOUNT = Pattern.compile(
            "\\b[A-Za-z]{2}\\d{2}[A-Za-z0-9]{10,30}\\b|\\b[A-Z]{2}\\d{2}(?: [A-Z0-9]{4}){2,7}(?: [A-Z0-9]{1,3})?\\b");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    /** International (+33 ...) or national (06 ...) numbers. Dates and amounts don't start that way. */
    private static final Pattern PHONE = Pattern.compile("(?<![\\w.+])(?:\\+\\d{1,3}|0)[ .-]?\\d(?:[ .-]?\\d){7,12}(?![\\w.])");

    private PiiMasker() {}

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = EMAIL.matcher(text).replaceAll("[email]");
        masked = ACCOUNT.matcher(masked).replaceAll("[account number]");
        masked = CARD.matcher(masked).replaceAll("[card number]");
        return PHONE.matcher(masked).replaceAll("[phone]");
    }
}
