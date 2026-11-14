package com.ebank.chatbot.conversation;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    /** Appending a message takes this lock, so sequence numbers have no gaps or duplicates. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Conversation c WHERE c.id = :id")
    Optional<Conversation> findForUpdate(@Param("id") UUID id);

    @Query("SELECT c FROM Conversation c WHERE c.customerId = :customerId AND c.state <> com.ebank.chatbot.conversation.ConversationState.RESOLVED")
    Optional<Conversation> findOpenByCustomer(@Param("customerId") String customerId);

    Page<Conversation> findByCustomerId(String customerId, Pageable pageable);

    List<Conversation> findByStateOrderByHandoffAtAsc(ConversationState state, Pageable pageable);

    List<Conversation> findByStateAndAdvisorIdOrderByAssignedAtAsc(ConversationState state, String advisorId);

    long countByStateAndAdvisorId(ConversationState state, String advisorId);
}
