package com.ebank.chatbot.web;

import com.ebank.chatbot.application.AdvisorChatService;
import com.ebank.chatbot.application.CustomerChatService;
import com.ebank.chatbot.conversation.Views.AdvisorConversationView;
import com.ebank.chatbot.conversation.Views.MessageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The advisor workspace (ADVISOR role). An advisor is "online" while subscribed to
 * {@code /user/queue/chat}: only then are conversations assigned to them automatically.
 */
@RestController
@RequestMapping("/api/v1/advisor")
class AdvisorChatController {

    private final AdvisorChatService service;

    AdvisorChatController(AdvisorChatService service) {
        this.service = service;
    }

    @GetMapping("/queue")
    List<AdvisorConversationView> queue() {
        return service.queue().stream().map(AdvisorConversationView::of).toList();
    }

    @GetMapping("/conversations")
    List<AdvisorConversationView> mine() {
        return service.mine().stream().map(AdvisorConversationView::of).toList();
    }

    @GetMapping("/conversations/{id}")
    AdvisorConversationView get(@PathVariable UUID id) {
        return AdvisorConversationView.of(service.get(id));
    }

    @GetMapping("/conversations/{id}/messages")
    List<MessageView> messages(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") @Min(0) long afterSeq,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit) {
        return service.messages(id, afterSeq, limit).stream().map(MessageView::of).toList();
    }

    /** 409 ALREADY_ASSIGNED if another advisor was faster. */
    @PostMapping("/conversations/{id}/claim")
    AdvisorConversationView claim(@PathVariable UUID id) {
        return AdvisorConversationView.of(service.claim(id));
    }

    @PostMapping("/conversations/{id}/messages")
    ResponseEntity<MessageView> send(@PathVariable UUID id, @Valid @RequestBody Requests.SendMessage request) {
        CustomerChatService.Sent sent = service.send(id, request.clientMessageId(), request.text());
        return ResponseEntity.status(sent.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(MessageView.of(sent.message()));
    }

    @PostMapping("/conversations/{id}/resolve")
    AdvisorConversationView resolve(@PathVariable UUID id) {
        return AdvisorConversationView.of(service.resolve(id));
    }

    @PostMapping("/conversations/{id}/read")
    ResponseEntity<Void> read(@PathVariable UUID id, @Valid @RequestBody Requests.MarkRead request) {
        service.markRead(id, request.seq());
        return ResponseEntity.noContent().build();
    }
}
