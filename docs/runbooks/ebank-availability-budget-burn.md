# EbankAvailabilityBudgetBurn

**Meaning.** The API spends its error budget too fast. The objective is 99.5% of API requests without a 5xx over 30 days (budget: 0.5%). `critical`: at this rate the month's budget is gone in about two days (14.4x over 1 h, or 6x over 6 h). `warning`: it will run out before the end of the 30 days (3x over 1 day, or 1x over 3 days). Actuator endpoints are not counted. Rules: [ebank-slo.yml](../../infra/observability/prometheus/rules/ebank-slo.yml).

**Impact.** Users see errors; transfers may be refused. A 5xx never moves money halfway: each transfer is one database transaction.

**Check.**
1. Which endpoints? In Prometheus:
   `sum by (uri, status) (rate(http_server_requests_seconds_count{service="ebank-backend", status=~"5.."}[5m]))`
2. How much budget is left? `ebank:slo_availability:budget_remaining_30d` (1 is untouched, 0 is spent).
3. Which errors? Search the logs for `INTERNAL_ERROR` and `SERVICE_UNAVAILABLE`, then open the `trace.id` in Jaeger to see the failing call.
4. `503 ANALYTICS_UNAVAILABLE` only on `/api/v1/analytics/**`: Elasticsearch is down. Transfers and notifications are unaffected.
5. Did it start with a deployment? `helm -n ebank-<env> history ebank`.

**Fix.**
- After a release: `helm -n ebank-<env> rollback ebank`.
- Database errors (pool exhausted, timeouts): see [ebank-latency-budget-burn.md](ebank-latency-budget-burn.md).
- Database login errors with dynamic credentials: see [ebank-db-credentials.md](ebank-db-credentials.md).
- Elasticsearch: restart it. The outbox replays the missed events on its own.

**Escalate.** Persistent 5xx on `/api/v1/transactions` after a rollback: stop and involve a backend developer before retrying anything. A `warning` that keeps coming back: the team decides whether to fix the cause or to freeze releases until the budget recovers.
