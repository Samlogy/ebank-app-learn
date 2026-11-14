package com.ebank.chatbot.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByConversationIdAndSeqGreaterThanOrderBySeqAsc(UUID conversationId, long afterSeq, Pageable pageable);

    /** The latest messages, newest first. */
    List<ChatMessage> findByConversationIdOrderBySeqDesc(UUID conversationId, Pageable pageable);

    Optional<ChatMessage> findByConversationIdAndSenderIdAndClientMessageId(UUID conversationId, String senderId, String clientMessageId);

    Optional<ChatMessage> findByIdAndConversationId(UUID id, UUID conversationId);

    @Query("SELECT max(m.seq) FROM ChatMessage m WHERE m.conversationId = :id AND m.senderType = :sender")
    Optional<Long> lastSeqOf(@Param("id") UUID conversationId, @Param("sender") SenderType sender);
}
