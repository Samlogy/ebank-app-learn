# EbankSlowRequests

**Meaning.** The 95th percentile API latency (actuator excluded) has been above 1 s for 10 minutes.

**Impact.** The app feels slow. Transfers time out after 10 s (database transaction timeout).

**Check.**
1. Which endpoints? `histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{service="ebank-backend"}[5m])))`
2. Open a slow trace in Jaeger: is the time spent in SQL, in Keycloak, in Elasticsearch or in the Claude API?
3. Database: `hikaricp_connections_pending` above 0 means the pool is exhausted. In SQL:
   `SELECT pid, now() - query_start AS age, wait_event_type, left(query, 80) FROM pg_stat_activity WHERE state <> 'idle' ORDER BY age DESC;`
   Many `Lock` waits on `accounts`: many simultaneous transfers on the same accounts (expected to queue, as they lock with `FOR UPDATE`).
4. JVM: long GC pauses come with [ebank-heap-almost-full.md](ebank-heap-almost-full.md).

**Fix.** Cancel a runaway query with `SELECT pg_cancel_backend(<pid>);`. Raise `DB_POOL_SIZE` (prod) only if PostgreSQL has room for it. Scaling the backend out is not possible yet (one replica, see the README).

**Escalate.** Lock waits that do not clear: a backend developer, as it may be a locking bug.
