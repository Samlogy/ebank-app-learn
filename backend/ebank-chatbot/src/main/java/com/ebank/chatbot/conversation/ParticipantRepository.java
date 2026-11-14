package com.ebank.chatbot.conversation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ParticipantRepository extends JpaRepository<Participant, Participant.Key> {

    List<Participant> findByKeyConversationId(UUID conversationId);
}
