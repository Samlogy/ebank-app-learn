# EbankOutboxBacklog

**Meaning.** More than 100 events are `PENDING` in `outbox_event`, or the oldest one has waited more than 5 minutes.

**Impact.** Money still moves, but notifications, emails and analytics fall behind.

**Check.**
1. Logs: `Outbox handler <name> failed on event ...` names the failing handler. The usual dependencies: SMTP (email fallback), Keycloak's Admin API (user email lookup), Elasticsearch (analytics).
2. Backlog by type:
   `SELECT event_type, status, count(*), min(occurred_at) FROM outbox_event WHERE status <> 'PUBLISHED' GROUP BY 1, 2;`
3. Is the relay running? `ebank.outbox.relay-enabled` must be `true`, and the logs must not repeat `Outbox poll failed`.

**Fix.** Bring the dependency back: the relay retries with backoff (2 s up to 15 min) and catches up on its own. Handlers are independent, so the others keep going. To retry right away after the fix:
`UPDATE outbox_event SET next_attempt_at = now() WHERE status = 'PENDING';`

**Escalate.** A backlog that grows while every dependency is up: a backend developer.
