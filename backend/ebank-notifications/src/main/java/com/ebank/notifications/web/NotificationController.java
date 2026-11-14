package com.ebank.notifications.web;

import com.ebank.common.web.PageResponse;
import com.ebank.notifications.application.NotificationService;
import com.ebank.notifications.application.NotificationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own notifications. New ones are also pushed live to {@code /user/queue/notifications}. */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService service;

    NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<NotificationView> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by("createdAt").descending().and(Sort.by("id")));
        return PageResponse.from(service.list(unreadOnly, request).map(NotificationView::of));
    }

    @GetMapping("/unread-count")
    UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(service.unreadCount());
    }

    @PatchMapping("/{id}/read")
    NotificationView markRead(@PathVariable UUID id) {
        return NotificationView.of(service.markRead(id));
    }

    @PostMapping("/read-all")
    MarkAllReadResponse markAllRead() {
        return new MarkAllReadResponse(service.markAllRead());
    }

    @GetMapping("/preferences")
    PreferencesResponse preferences() {
        return PreferencesResponse.of(service.preferences());
    }

    @PutMapping("/preferences")
    PreferencesResponse updatePreferences(@Valid @RequestBody PreferencesRequest request) {
        return PreferencesResponse.of(service.updatePreferences(request.emailEnabled(), request.smsEnabled()));
    }
}
