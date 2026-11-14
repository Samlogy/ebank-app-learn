package com.ebank.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ebank.analytics.application.AnalyticsStore.CurrencyTotals;
import com.ebank.analytics.application.AnalyticsStore.Direction;
import com.ebank.analytics.application.AnalyticsStore.Sum;
import com.ebank.analytics.application.AnalyticsViews.CurrencySummary;
import com.ebank.auth.api.CurrentUser;
import com.ebank.common.error.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T15:00:00Z"), ZoneOffset.UTC);

    @Mock AnalyticsStore store;
    @Mock CurrentUser currentUser;
    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(store, currentUser, CLOCK);
        lenient().when(currentUser.userId()).thenReturn("alice");
        lenient().doCallRealMethod().when(currentUser).requireAdmin();
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
    }

    @Test
    void defaultRangeIsTheLast30DaysIncludingToday() {
        AnalyticsService.Range range = service.range(null, null);

        assertThat(range.from()).isEqualTo(LocalDate.parse("2026-10-16"));
        assertThat(range.to()).isEqualTo(LocalDate.parse("2026-11-14"));
        assertThat(range.start()).isEqualTo(Instant.parse("2026-10-16T00:00:00Z"));
        assertThat(range.end()).isEqualTo(Instant.parse("2026-11-15T00:00:00Z"));
    }

    @Test
    void rangesAreValidated() {
        assertCode(() -> service.range(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-01")), "INVALID_RANGE");
        assertCode(() -> service.range(LocalDate.parse("2025-01-01"), LocalDate.parse("2026-11-01")), "RANGE_TOO_LONG");
        assertThat(service.range(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")).to()).isEqualTo("2026-12-31");
    }

    @Test
    void summaryTurnsMinorUnitsIntoDecimalsAtTheCurrencyScale() {
        when(store.summary(eq("alice"), isNull(), any(), any())).thenReturn(List.of(
                new CurrencyTotals("EUR", new Sum(10000, 1), new Sum(3050, 2), new Sum(1000, 1), 1),
                new CurrencyTotals("BTC", new Sum(1, 1), Sum.ZERO, Sum.ZERO, 0)));

        List<CurrencySummary> totals = service.summary(null, null, " ").currencies();

        CurrencySummary eur = totals.get(0);
        assertThat(eur.incoming().total()).isEqualTo(new BigDecimal("100.00"));
        assertThat(eur.outgoing().total()).isEqualTo(new BigDecimal("30.50"));
        assertThat(eur.outgoing().count()).isEqualTo(2);
        assertThat(eur.net()).isEqualTo(new BigDecimal("69.50"));
        assertThat(eur.cancelledCount()).isEqualTo(1);
        assertThat(totals.get(1).incoming().total()).isEqualTo(new BigDecimal("0.00000001"));
    }

    @Test
    void queriesAreAlwaysScopedToTheCaller() {
        service.summary(null, null, "EB123");

        verify(store).summary(eq("alice"), eq("EB123"), any(), any());
    }

    @Test
    void currencyAndLimitAreValidated() {
        assertCode(() -> service.timeline("XXX", null, null, null, null), "UNSUPPORTED_CURRENCY");
        assertCode(() -> service.counterparties("EUR", Direction.OUTGOING, 21, null, null), "INVALID_LIMIT");
        assertCode(() -> service.counterparties("EUR", Direction.OUTGOING, 0, null, null), "INVALID_LIMIT");
        service.counterparties("eur", null, null, null, null);
        verify(store).counterparties(eq("alice"), eq("EUR"), eq(Direction.OUTGOING), anyInt(), any(), any());
    }

    @Test
    void overviewIsForAdminsOnly() {
        assertCode(() -> service.overview(null, null), "FORBIDDEN");
        verifyNoInteractions(store);
    }
}
