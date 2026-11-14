package com.ebank.analytics.web;

import com.ebank.analytics.application.AnalyticsService;
import com.ebank.analytics.application.AnalyticsStore.Direction;
import com.ebank.analytics.application.AnalyticsStore.Interval;
import com.ebank.analytics.application.AnalyticsViews.Counterparties;
import com.ebank.analytics.application.AnalyticsViews.Overview;
import com.ebank.analytics.application.AnalyticsViews.Summary;
import com.ebank.analytics.application.AnalyticsViews.Timeline;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Figures computed from the caller's own transactions. {@code from} and {@code to} are inclusive
 * days in UTC ({@code yyyy-MM-dd}); without them, the last 30 days. Data is eventually consistent:
 * a transaction appears a moment after it is booked.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@ConditionalOnProperty(name = "ebank.analytics.enabled", havingValue = "true", matchIfMissing = true)
class AnalyticsController {

    private final AnalyticsService service;

    AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String accountId) {
        return service.summary(from, to, accountId);
    }

    @GetMapping("/timeline")
    Timeline timeline(
            @RequestParam String currency,
            @RequestParam(required = false) Interval interval,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String accountId) {
        return service.timeline(currency, interval, from, to, accountId);
    }

    @GetMapping("/counterparties")
    Counterparties counterparties(
            @RequestParam String currency,
            @RequestParam(required = false) Direction direction,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.counterparties(currency, direction, limit, from, to);
    }

    /** Admin only: bank-wide volumes, without any per-customer figures. */
    @GetMapping("/overview")
    Overview overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.overview(from, to);
    }
}
