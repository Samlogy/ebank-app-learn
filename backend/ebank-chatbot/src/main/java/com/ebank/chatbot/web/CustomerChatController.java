package com.ebank.chatbot.web;

import com.ebank.chatbot.application.CustomerChatService;
import com.ebank.chatbot.conversation.Views.CustomerConversationView;
import com.ebank.chatbot.conversation.Views.MessageView;
import com.ebank.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's chat. Writes go through REST; new messages, state changes, read receipts and the
 * assistant's typing indicator are pushed on {@code /user/queue/chat}.
 */
@RestController
@RequestMapping("/api/v1/chat/conversations")
class CustomerChatController {

    private final CustomerChatService service;

    CustomerChatController(CustomerChatService service) {
        this.service = service;
    }

    /** The open conversation, or a new one. */
    @PostMapping
    CustomerConversationView open() {
        return CustomerConversationView.of(service.open());
    }

    @GetMapping
    PageResponse<CustomerConversationView> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(service.list(PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .map(CustomerConversationView::of));
    }

    @GetMapping("/{id}")
    CustomerConversationView get(@PathVariable UUID id) {
        return CustomerConversationView.of(service.get(id));
    }

    /** Messages after {@code afterSeq}, oldest first: use the last seq you have to catch up after a reconnect. */
    @GetMapping("/{id}/messages")
    List<MessageView> messages(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) long afterSeq,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.messages(id, afterSeq, limit).stream().map(MessageView::of).toList();
    }

    /** 201 when stored, 200 when the same clientMessageId was already sent. */
    @PostMapping("/{id}/messages")
    ResponseEntity<MessageView> send(@PathVariable UUID id, @Valid @RequestBody Requests.SendMessage request) {
        CustomerChatService.Sent sent = service.send(id, request.clientMessageId(), request.text());
        return ResponseEntity.status(sent.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(MessageView.of(sent.message()));
    }

    @DeleteMapping("/{id}/messages/{messageId}")
    MessageView delete(@PathVariable UUID id, @PathVariable UUID messageId) {
        return MessageView.of(service.delete(id, messageId));
    }

    /** "Talk to an advisor". */
    @PostMapping("/{id}/handoff")
    CustomerConversationView handoff(@PathVariable UUID id) {
        return CustomerConversationView.of(service.handoff(id));
    }

    @PostMapping("/{id}/back-to-bot")
    CustomerConversationView backToBot(@PathVariable UUID id) {
        return CustomerConversationView.of(service.backToBot(id));
    }

    @PostMapping("/{id}/resolve")
    CustomerConversationView resolve(@PathVariable UUID id) {
        return CustomerConversationView.of(service.resolve(id));
    }

    @PostMapping("/{id}/read")
    ResponseEntity<Void> read(@PathVariable UUID id, @Valid @RequestBody Requests.MarkRead request) {
        service.markRead(id, request.seq());
        return ResponseEntity.noContent().build();
    }
}
