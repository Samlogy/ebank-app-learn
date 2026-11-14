package com.ebank.transactions.application;

import com.ebank.auth.api.CurrentUser;
import com.ebank.common.error.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger as CSV, for accounting and reconciliation: every entry booked on the given UTC days,
 * oldest first. Admins only.
 *
 * <p>Only closed days can be exported (up to yesterday), and entries are never changed, so two
 * exports of the same range are identical byte for byte. Rows are streamed from the database, so
 * memory use does not grow with the range.
 */
@Service
public class LedgerExport {

    static final int MAX_RANGE_DAYS = 31;
    static final String HEADER = "entry_id,transfer_id,transaction_id,account_id,type,amount,currency,booked_at\n";
    private static final int FETCH_SIZE = 500;
    private static final Logger log = LoggerFactory.getLogger(LedgerExport.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final CurrentUser currentUser;
    private final Clock clock;

    @Autowired
    LedgerExport(JdbcTemplate jdbc, PlatformTransactionManager transactions, CurrentUser currentUser) {
        this(jdbc, transactions, currentUser, Clock.systemUTC());
    }

    LedgerExport(JdbcTemplate jdbc, PlatformTransactionManager transactions, CurrentUser currentUser, Clock clock) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** Inclusive UTC days, as [start of from, start of the day after to). */
    public record Range(LocalDate from, LocalDate to, Instant start, Instant end) {}

    /** Checks the caller and the range. Call it before the response starts: errors are still JSON. */
    public Range prepare(LocalDate from, LocalDate to) {
        currentUser.requireAdmin();
        if (from == null || to == null) {
            throw ApiException.unprocessable("INVALID_RANGE", "'from' and 'to' are required.");
        }
        if (from.isAfter(to)) {
            throw ApiException.unprocessable("INVALID_RANGE", "'from' must not be after 'to'.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw ApiException.unprocessable("RANGE_TOO_LONG", "The range can cover at most " + MAX_RANGE_DAYS + " days.");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!to.isBefore(today)) {
            throw ApiException.unprocessable("DAY_NOT_CLOSED", "Only closed days can be exported: 'to' must be before " + today + " (UTC).");
        }
        Range range = new Range(from, to, from.atStartOfDay(ZoneOffset.UTC).toInstant(), to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        // Who exported what: internal IDs only.
        log.info("Ledger export {}..{} by {}", from, to, currentUser.userId());
        return range;
    }

    public long count(Range range) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE created_at >= ? AND created_at < ?",
                Long.class, Timestamp.from(range.start()), Timestamp.from(range.end()));
        return count == null ? 0 : count;
    }

    /** Writes the CSV. Values are UUIDs, account IDs, enums, numbers and dates: nothing to quote. */
    public void write(Range range, Writer out) {
        // A transaction, so PostgreSQL can use a cursor; read-only, as nothing is written.
        readOnly.executeWithoutResult(status -> {
            try {
                out.write(HEADER);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            // A cursor (fetch size inside a transaction), not the whole result set in memory.
            jdbc.query(con -> {
                var statement = con.prepareStatement("""
                        SELECT e.id, e.transfer_id, t.transaction_id, e.account_id, e.type, e.amount, e.currency, e.created_at
                        FROM ledger_entries e JOIN transfers t ON t.id = e.transfer_id
                        WHERE e.created_at >= ? AND e.created_at < ?
                        ORDER BY e.created_at, e.id
                        """);
                statement.setFetchSize(FETCH_SIZE);
                statement.setTimestamp(1, Timestamp.from(range.start()));
                statement.setTimestamp(2, Timestamp.from(range.end()));
                return statement;
            }, rs -> {
                try {
                    out.write(rs.getString(1) + ',' + rs.getString(2) + ',' + rs.getString(3) + ',' + rs.getString(4) + ','
                            + rs.getString(5) + ',' + rs.getBigDecimal(6).toPlainString() + ',' + rs.getString(7) + ','
                            + rs.getTimestamp(8).toInstant() + '\n');
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        });
    }
}
