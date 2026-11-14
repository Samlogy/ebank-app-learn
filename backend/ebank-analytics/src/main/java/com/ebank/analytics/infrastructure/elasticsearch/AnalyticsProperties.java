package com.ebank.analytics.infrastructure.elasticsearch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled off turns the whole module off: no indexing and no endpoints
 * @param index alias that reads and writes go through. The concrete index is {@code <index>-v1};
 *     a mapping change creates {@code -v2}, reindexes, and moves the alias.
 */
@ConfigurationProperties("ebank.analytics")
record AnalyticsProperties(@DefaultValue("true") boolean enabled, @DefaultValue("ebank-transactions") String index) {

    String concreteIndex() {
        return index + "-v1";
    }
}
