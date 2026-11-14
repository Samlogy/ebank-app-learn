package com.ebank.analytics.application;

import com.ebank.analytics.application.AnalyticsStore.Direction;
import com.ebank.analytics.application.AnalyticsStore.Interval;
import com.ebank.analytics.application.AnalyticsStore.Sum;
import com.ebank.analytics.application.AnalyticsViews.Amount;
import com.ebank.analytics.application.AnalyticsViews.CounterpartyTotal;
import com.ebank.analytics.application.AnalyticsViews.Counterparties;
import com.ebank.analytics.application.AnalyticsViews.CurrencyOverview;
import com.ebank.analytics.application.AnalyticsViews.CurrencySummary;
import com.ebank.analytics.application.AnalyticsViews.Overview;
import com.ebank.analytics.application.AnalyticsViews.Summary;
import com.ebank.analytics.application.AnalyticsViews.Timeline;
import com.ebank.analytics.application.AnalyticsViews.TimelinePoint;
import com.ebank.auth.api.CurrentUser;
import com.ebank.common.error.ApiException;
import com.ebank.common.money.Currencies;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Read side of analytics. Customers only see transactions they took part in; an account filter
 * on someone else's account simply matches nothing. Admins get bank-wide totals from
 * {@link #overview}, not other people's personal figures.
 */
@Service
@ConditionalOnProperty(name = "ebank.analytics.enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsService {

    static final int MAX_RANGE_DAYS = 366;
    static final int DEFAULT_RANGE_DAYS = 30;
    static final int MAX_COUNTERPARTIES = 20;

    private final AnalyticsStore store;
    private final CurrentUser currentUser;
    private final Clock clock;

    @Autowired
    public AnalyticsService(AnalyticsStore store, CurrentUser currentUser) {
        this(store, currentUser, Clock.systemUTC());
    }

    AnalyticsService(AnalyticsStore store, CurrentUser currentUser, Clock clock) {
        this.store = store;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** Inclusive UTC days, as [start of from, start of the day after to). */
    record Range(LocalDate from, LocalDate to) {
        Instant start() {
            return from.atStartOfDay(ZoneOffset.UTC).toInstant();
        }

        Instant end() {
            return to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
    }

    Range range(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (start.isAfter(end)) {
            throw ApiException.unprocessable("INVALID_RANGE", "'from' must not be after 'to'.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw ApiException.unprocessable("RANGE_TOO_LONG", "The range can cover at most " + MAX_RANGE_DAYS + " days.");
        }
        return new Range(start, end);
    }

    public Summary summary(LocalDate from, LocalDate to, String accountId) {
        Range range = range(from, to);
        var totals = store.summary(currentUser.userId(), blankToNull(accountId), range.start(), range.end()).stream()
                .map(t -> new CurrencySummary(
                        t.currency(),
                        amount(t.incoming(), t.currency()),
                        amount(t.outgoing(), t.currency()),
                        amount(t.internal(), t.currency()),
                        decimal(t.incoming().minor() - t.outgoing().minor(), t.currency()),
                        t.cancelledCount()))
                .toList();
        return new Summary(range.from(), range.to(), blankToNull(accountId), totals);
    }

    public Timeline timeline(String currency, Interval interval, LocalDate from, LocalDate to, String accountId) {
        String code = currency(currency);
        Range range = range(from, to);
        Interval step = interval != null ? interval : Interval.DAY;
        var points = store.timeline(currentUser.userId(), blankToNull(accountId), code, step, range.start(), range.end()).stream()
                .map(b -> new TimelinePoint(
                        LocalDate.ofInstant(b.start(), ZoneOffset.UTC),
                        decimal(b.incoming().minor(), code),
                        decimal(b.outgoing().minor(), code),
                        decimal(b.incoming().minor() - b.outgoing().minor(), code)))
                .toList();
        return new Timeline(code, step, range.from(), range.to(), blankToNull(accountId), points);
    }

    public Counterparties counterparties(String currency, Direction direction, Integer limit, LocalDate from, LocalDate to) {
        String code = currency(currency);
        Range range = range(from, to);
        Direction side = direction != null ? direction : Direction.OUTGOING;
        int size = limit != null ? limit : 5;
        if (size < 1 || size > MAX_COUNTERPARTIES) {
            throw ApiException.unprocessable("INVALID_LIMIT", "'limit' must be between 1 and " + MAX_COUNTERPARTIES + ".");
        }
        var items = store.counterparties(currentUser.userId(), code, side, size, range.start(), range.end()).stream()
                .map(c -> new CounterpartyTotal(c.accountId(), decimal(c.total().minor(), code), c.total().count()))
                .toList();
        return new Counterparties(code, side, range.from(), range.to(), items);
    }

    public Overview overview(LocalDate from, LocalDate to) {
        currentUser.requireAdmin();
        Range range = range(from, to);
        var currencies = store.overview(range.start(), range.end()).stream()
                .map(o -> new CurrencyOverview(
                        o.currency(),
                        amount(o.transfers(), o.currency()),
                        amount(o.deposits(), o.currency()),
                        amount(o.cancelled(), o.currency()),
                        o.activeUsers()))
                .toList();
        return new Overview(range.from(), range.to(), currencies);
    }

    private static String currency(String currency) {
        String code = currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
        if (!Currencies.isSupported(code)) {
            throw ApiException.unprocessable("UNSUPPORTED_CURRENCY", "This currency is not supported.");
        }
        return code;
    }

    private static Amount amount(Sum sum, String currency) {
        return new Amount(decimal(sum.minor(), currency), sum.count());
    }

    static BigDecimal decimal(long minor, String currency) {
        return BigDecimal.valueOf(minor, Currencies.isSupported(currency) ? Currencies.maxScale(currency) : 2);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
