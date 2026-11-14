package com.ebank.analytics.application;

import com.ebank.analytics.application.AnalyticsStore.Direction;
import com.ebank.analytics.application.AnalyticsStore.Interval;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** What the analytics API returns. Amounts are decimals at the currency's scale, never floats. */
public final class AnalyticsViews {

    private AnalyticsViews() {}

    public record Amount(BigDecimal total, long count) {}

    /** {@code from} and {@code to} are inclusive UTC days. */
    public record Summary(LocalDate from, LocalDate to, String accountId, List<CurrencySummary> currencies) {}

    /**
     * @param net incoming minus outgoing. Internal moves do not change it.
     * @param cancelledCount outgoing transfers that were refused, such as for insufficient funds
     */
    public record CurrencySummary(
            String currency, Amount incoming, Amount outgoing, Amount internal, BigDecimal net, long cancelledCount) {}

    public record Timeline(
            String currency, Interval interval, LocalDate from, LocalDate to, String accountId, List<TimelinePoint> points) {}

    public record TimelinePoint(LocalDate start, BigDecimal incoming, BigDecimal outgoing, BigDecimal net) {}

    public record Counterparties(String currency, Direction direction, LocalDate from, LocalDate to, List<CounterpartyTotal> items) {}

    /** {@code accountId} is {@code EXTERNAL-<currency>} for money from outside the bank. */
    public record CounterpartyTotal(String accountId, BigDecimal total, long count) {}

    public record Overview(LocalDate from, LocalDate to, List<CurrencyOverview> currencies) {}

    /** {@code activeUsers} is approximate above a few thousand (Elasticsearch cardinality). */
    public record CurrencyOverview(String currency, Amount transfers, Amount deposits, Amount cancelled, long activeUsers) {}
}
