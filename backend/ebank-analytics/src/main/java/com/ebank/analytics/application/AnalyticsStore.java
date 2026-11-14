package com.ebank.analytics.application;

import java.time.Instant;
import java.util.List;

/**
 * Where facts are kept and aggregated. All amounts are sums of minor units; the caller turns them
 * into decimals. Every query is scoped to one user unless stated otherwise.
 *
 * <p>Implementations throw {@link AnalyticsUnavailableException} when the store cannot be reached.
 */
public interface AnalyticsStore {

    /** Insert or replace, keyed by transaction ID. */
    void save(TransactionFact fact);

    /**
     * @param accountId null for all the user's accounts. Moves between the user's own accounts are
     *     then reported as internal, not as incoming and outgoing. With an account, they count as
     *     incoming or outgoing for that account.
     */
    List<CurrencyTotals> summary(String userId, String accountId, Instant from, Instant to);

    List<Bucket> timeline(String userId, String accountId, String currency, Interval interval, Instant from, Instant to);

    List<Counterparty> counterparties(String userId, String currency, Direction direction, int limit, Instant from, Instant to);

    /** Bank-wide, for admins. */
    List<CurrencyOverview> overview(Instant from, Instant to);

    enum Interval { DAY, WEEK, MONTH }

    enum Direction { INCOMING, OUTGOING }

    record Sum(long minor, long count) {
        public static final Sum ZERO = new Sum(0, 0);
    }

    record CurrencyTotals(String currency, Sum incoming, Sum outgoing, Sum internal, long cancelledCount) {}

    /** {@code start} is the first instant of the bucket, in UTC. */
    record Bucket(Instant start, Sum incoming, Sum outgoing) {}

    record Counterparty(String accountId, Sum total) {}

    record CurrencyOverview(String currency, Sum transfers, Sum deposits, Sum cancelled, long activeUsers) {}
}
