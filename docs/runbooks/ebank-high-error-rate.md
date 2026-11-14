# EbankHighErrorRate

**Meaning.** More than 5% of API requests returned a 5xx over 5 minutes.

**Impact.** Users see errors; transfers may be refused. A 5xx never moves money halfway: each transfer is one database transaction.

**Check.**
1. Which endpoints? In Prometheus:
   `sum by (uri, status) (rate(http_server_requests_seconds_count{service="ebank-backend", status=~"5.."}[5m]))`
2. Which errors? Search the logs for `INTERNAL_ERROR` and `SERVICE_UNAVAILABLE`, then open the `trace.id` in Jaeger to see the failing call.
3. `503 ANALYTICS_UNAVAILABLE` only on `/api/v1/analytics/**`: Elasticsearch is down. Transfers and notifications are unaffected.
4. Did it start with a deployment? `helm -n ebank-<env> history ebank`.

**Fix.**
- After a release: `helm -n ebank-<env> rollback ebank`.
- Database errors (pool exhausted, timeouts): see [ebank-slow-requests.md](ebank-slow-requests.md).
- Elasticsearch: restart it. The outbox replays the missed events on its own.

**Escalate.** Persistent 5xx on `/api/v1/transactions` after a rollback: stop and involve a backend developer before retrying anything.
