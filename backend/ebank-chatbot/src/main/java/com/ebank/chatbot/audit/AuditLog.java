package com.ebank.chatbot.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Who did what in which conversation: tool calls, handoffs, assignments, resolutions.
 * Never message content, tool arguments or results.
 */
@Component
public class AuditLog {

    public enum Action { TOOL_CALL, HANDOFF, ASSIGN, BACK_TO_BOT, RESOLVE, MESSAGE_DELETED, ASSISTANT_ERROR }

    private final JdbcTemplate jdbc;

    AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Joins the caller's transaction when there is one, so the record commits with the change. */
    public void record(String actorId, UUID conversationId, Action action, String detail) {
        jdbc.update("""
                INSERT INTO chatbot_audit (id, occurred_at, actor_id, conversation_id, action, detail)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), Timestamp.from(Instant.now()), actorId, conversationId, action.name(),
                detail == null ? null : detail.length() > 255 ? detail.substring(0, 255) : detail);
    }
}
