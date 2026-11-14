package com.ebank.analytics.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval;
import co.elastic.clients.elasticsearch._types.aggregations.FieldDateMath;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.util.NamedValue;
import com.ebank.analytics.application.AnalyticsStore;
import com.ebank.analytics.application.AnalyticsUnavailableException;
import com.ebank.analytics.application.TransactionFact;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Facts live in one index with a strict mapping: an unknown field is rejected rather than guessed.
 * Analytics indices are kept apart from log indices ({@code ebank-transactions*} vs ELK's own).
 */
@Component
@EnableConfigurationProperties(AnalyticsProperties.class)
@ConditionalOnProperty(name = "ebank.analytics.enabled", havingValue = "true", matchIfMissing = true)
class ElasticsearchAnalyticsStore implements AnalyticsStore {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchAnalyticsStore.class);
    private static final String AMOUNT = "amountMinor";
    private static final String OCCURRED_AT = "occurredAt";
    private static final int MAX_CURRENCIES = 50;

    private final ElasticsearchClient client;
    private final AnalyticsProperties properties;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    ElasticsearchAnalyticsStore(ElasticsearchClient client, AnalyticsProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ index

    /** Elasticsearch may be down at startup: the index is then created on the first write. */
    @EventListener(ApplicationReadyEvent.class)
    void createIndexAtStartup() {
        try {
            ensureIndex();
        } catch (AnalyticsUnavailableException ex) {
            log.warn("Elasticsearch unavailable at startup, the analytics index will be created on first use");
        }
    }

    void ensureIndex() {
        if (indexReady.get()) {
            return;
        }
        call(() -> {
            if (!client.indices().existsAlias(e -> e.name(properties.index())).value()) {
                try {
                    client.indices().create(c -> c
                            .index(properties.concreteIndex())
                            .aliases(properties.index(), a -> a.isWriteIndex(true))
                            .mappings(m -> m
                                    .dynamic(DynamicMapping.Strict)
                                    .properties("transactionId", p -> p.keyword(k -> k))
                                    .properties("kind", p -> p.keyword(k -> k))
                                    .properties("status", p -> p.keyword(k -> k))
                                    .properties("senderAccountId", p -> p.keyword(k -> k))
                                    .properties("receiverAccountId", p -> p.keyword(k -> k))
                                    .properties("senderUserId", p -> p.keyword(k -> k))
                                    .properties("receiverUserId", p -> p.keyword(k -> k))
                                    .properties("participants", p -> p.keyword(k -> k))
                                    .properties("internal", p -> p.boolean_(b -> b))
                                    .properties("currency", p -> p.keyword(k -> k))
                                    .properties(AMOUNT, p -> p.long_(l -> l))
                                    .properties("scale", p -> p.integer(i -> i.index(false)))
                                    .properties("failureReason", p -> p.keyword(k -> k))
                                    .properties(OCCURRED_AT, p -> p.date(d -> d))));
                    log.info("Created analytics index {} behind alias {}", properties.concreteIndex(), properties.index());
                } catch (ElasticsearchException ex) {
                    // Another instance created it in the meantime.
                    if (!"resource_already_exists_exception".equals(ex.error().type())) {
                        throw ex;
                    }
                }
            }
            indexReady.set(true);
            return null;
        });
    }

    // ------------------------------------------------------------------ writes

    @Override
    public void save(TransactionFact fact) {
        ensureIndex();
        Map<String, Object> document = new HashMap<>();
        document.put("transactionId", fact.transactionId());
        document.put("kind", fact.kind().name());
        document.put("status", fact.status().name());
        document.put("senderAccountId", fact.senderAccountId());
        document.put("receiverAccountId", fact.receiverAccountId());
        document.put("senderUserId", fact.senderUserId());
        document.put("receiverUserId", fact.receiverUserId());
        document.put("participants", fact.participants());
        document.put("internal", fact.internal());
        document.put("currency", fact.currency());
        document.put(AMOUNT, fact.amountMinor());
        document.put("scale", fact.scale());
        document.put("failureReason", fact.failureReason());
        document.put(OCCURRED_AT, fact.occurredAt().toString());
        call(() -> client.index(i -> i.index(properties.index()).id(fact.transactionId()).document(document)));
    }

    // ------------------------------------------------------------------ reads

    @Override
    public List<CurrencyTotals> summary(String userId, String accountId, Instant from, Instant to) {
        Map<String, Aggregation> sides = Map.of(
                "incoming", sumWhere(incoming(userId, accountId)),
                "outgoing", sumWhere(outgoing(userId, accountId)),
                "internal", sumWhere(accountId == null ? internalMove(userId) : nothing()),
                "cancelled", Aggregation.of(a -> a.filter(and(term("status", "CANCELLED"), term("senderUserId", userId)))));
        SearchResponse<Void> response = search(visibleTo(userId, accountId, from, to),
                Map.of("currencies", byCurrency(sides)));
        return currencyBuckets(response).stream()
                .map(b -> new CurrencyTotals(
                        b.key().stringValue(),
                        sum(b.aggregations().get("incoming")),
                        sum(b.aggregations().get("outgoing")),
                        sum(b.aggregations().get("internal")),
                        b.aggregations().get("cancelled").filter().docCount()))
                .toList();
    }

    @Override
    public List<Bucket> timeline(String userId, String accountId, String currency, Interval interval, Instant from, Instant to) {
        Query scope = and(visibleTo(userId, accountId, from, to), term("currency", currency));
        Aggregation histogram = Aggregation.of(a -> a
                .dateHistogram(h -> h
                        .field(OCCURRED_AT)
                        .calendarInterval(switch (interval) {
                            case DAY -> CalendarInterval.Day;
                            case WEEK -> CalendarInterval.Week;
                            case MONTH -> CalendarInterval.Month;
                        })
                        .timeZone("UTC")
                        .minDocCount(0)
                        .extendedBounds(e -> e
                                .min(FieldDateMath.of(m -> m.value(from.toEpochMilli())))
                                .max(FieldDateMath.of(m -> m.value(to.minusMillis(1).toEpochMilli())))))
                .aggregations("incoming", sumWhere(incoming(userId, accountId)))
                .aggregations("outgoing", sumWhere(outgoing(userId, accountId))));
        SearchResponse<Void> response = search(scope, Map.of("timeline", histogram));
        return response.aggregations().get("timeline").dateHistogram().buckets().array().stream()
                .map(b -> new Bucket(
                        Instant.ofEpochMilli(b.key()),
                        sum(b.aggregations().get("incoming")),
                        sum(b.aggregations().get("outgoing"))))
                .toList();
    }

    @Override
    public List<Counterparty> counterparties(String userId, String currency, Direction direction, int limit, Instant from, Instant to) {
        boolean outgoing = direction == Direction.OUTGOING;
        Query scope = and(
                visibleTo(userId, null, from, to),
                term("currency", currency),
                term("status", "DONE"),
                term("internal", false),
                term(outgoing ? "senderUserId" : "receiverUserId", userId));
        Aggregation top = Aggregation.of(a -> a
                .terms(t -> t
                        .field(outgoing ? "receiverAccountId" : "senderAccountId")
                        .size(limit)
                        .order(List.of(NamedValue.of("total", SortOrder.Desc))))
                .aggregations("total", s -> s.sum(sum -> sum.field(AMOUNT))));
        SearchResponse<Void> response = search(scope, Map.of("top", top));
        return response.aggregations().get("top").sterms().buckets().array().stream()
                .map(b -> new Counterparty(
                        b.key().stringValue(),
                        new Sum(exact(b.aggregations().get("total").sum().value()), b.docCount())))
                .toList();
    }

    @Override
    public List<CurrencyOverview> overview(Instant from, Instant to) {
        Map<String, Aggregation> parts = Map.of(
                "transfers", sumWhere(and(term("status", "DONE"), term("kind", "TRANSFER"))),
                "deposits", sumWhere(and(term("status", "DONE"), term("kind", "DEPOSIT"))),
                "cancelled", sumWhere(term("status", "CANCELLED")),
                "users", Aggregation.of(a -> a.cardinality(c -> c.field("participants"))));
        SearchResponse<Void> response = search(between(from, to), Map.of("currencies", byCurrency(parts)));
        return currencyBuckets(response).stream()
                .map(b -> new CurrencyOverview(
                        b.key().stringValue(),
                        sum(b.aggregations().get("transfers")),
                        sum(b.aggregations().get("deposits")),
                        sum(b.aggregations().get("cancelled")),
                        b.aggregations().get("users").cardinality().value()))
                .toList();
    }

    // ------------------------------------------------------------------ query parts

    /** Only facts the user took part in, optionally narrowed to one of their accounts. */
    private static Query visibleTo(String userId, String accountId, Instant from, Instant to) {
        Query base = and(between(from, to), term("participants", userId));
        if (accountId == null) {
            return base;
        }
        return and(base, Query.of(q -> q.bool(b -> b
                .should(and(term("senderAccountId", accountId), term("senderUserId", userId)))
                .should(and(term("receiverAccountId", accountId), term("receiverUserId", userId)))
                .minimumShouldMatch("1"))));
    }

    /** Money that reached the user (or the account) from someone else, or from outside the bank. */
    private static Query incoming(String userId, String accountId) {
        return accountId == null
                ? and(term("status", "DONE"), term("receiverUserId", userId), term("internal", false))
                : and(term("status", "DONE"), term("receiverUserId", userId), term("receiverAccountId", accountId));
    }

    private static Query outgoing(String userId, String accountId) {
        return accountId == null
                ? and(term("status", "DONE"), term("senderUserId", userId), term("internal", false))
                : and(term("status", "DONE"), term("senderUserId", userId), term("senderAccountId", accountId));
    }

    private static Query internalMove(String userId) {
        return and(term("status", "DONE"), term("senderUserId", userId), term("internal", true));
    }

    private static Query nothing() {
        return Query.of(q -> q.matchNone(m -> m));
    }

    private static Query between(Instant from, Instant to) {
        return Query.of(q -> q.range(r -> r.date(d -> d.field(OCCURRED_AT).gte(from.toString()).lt(to.toString()))));
    }

    private static Query term(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(FieldValue.of(value))));
    }

    private static Query term(String field, boolean value) {
        return Query.of(q -> q.term(t -> t.field(field).value(FieldValue.of(value))));
    }

    private static Query and(Query... queries) {
        return Query.of(q -> q.bool(b -> b.filter(List.of(queries))));
    }

    private static Aggregation sumWhere(Query filter) {
        return Aggregation.of(a -> a.filter(filter).aggregations("total", s -> s.sum(sum -> sum.field(AMOUNT))));
    }

    private static Aggregation byCurrency(Map<String, Aggregation> sub) {
        return Aggregation.of(a -> a.terms(t -> t.field("currency").size(MAX_CURRENCIES)).aggregations(sub));
    }

    private static List<StringTermsBucket> currencyBuckets(SearchResponse<Void> response) {
        return response.aggregations().get("currencies").sterms().buckets().array();
    }

    private static Sum sum(Aggregate filtered) {
        var bucket = filtered.filter();
        return bucket.docCount() == 0
                ? Sum.ZERO
                : new Sum(exact(bucket.aggregations().get("total").sum().value()), bucket.docCount());
    }

    /** Sums of longs come back as doubles. They are exact below 2^53 minor units. */
    private static long exact(double value) {
        if (Math.abs(value) >= 0x1p53) {
            throw new IllegalStateException("Analytics sum is too large to be exact");
        }
        return Math.round(value);
    }

    private SearchResponse<Void> search(Query query, Map<String, Aggregation> aggregations) {
        ensureIndex();
        return call(() -> client.search(s -> s
                .index(properties.index())
                .size(0)
                .trackTotalHits(t -> t.enabled(false))
                .query(query)
                .aggregations(aggregations), Void.class));
    }

    @FunctionalInterface
    private interface Call<T> {
        T run() throws IOException;
    }

    /** Connection problems and server errors become one exception the callers know how to handle. */
    private static <T> T call(Call<T> action) {
        try {
            return action.run();
        } catch (IOException | ElasticsearchException ex) {
            throw new AnalyticsUnavailableException(ex);
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException) {
                throw new AnalyticsUnavailableException(ex);
            }
            throw ex;
        }
    }
}
