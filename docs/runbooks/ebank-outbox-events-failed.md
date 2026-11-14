# EbankOutboxEventsFailed

**Meaning.** At least one event gave up after 10 attempts and is `FAILED`. It is no longer retried.

**Impact.** Some users missed a notification, or analytics miss a transaction. The ledger is not affected.

**Check.**
```sql
SELECT id, event_type, attempts, last_error, occurred_at
FROM outbox_event WHERE status = 'FAILED' ORDER BY occurred_at;
```
`last_error` holds the exception type only (never its message, which could contain personal data). Find the matching `Outbox event <id> ... was marked FAILED` line in the logs for the stack trace.

**Fix.** Fix the cause first (dependency, bad data, bug), then send the events back to the relay:
```sql
UPDATE outbox_event SET status = 'PENDING', attempts = 0, next_attempt_at = now()
WHERE status = 'FAILED' AND id IN ('...');
```
Handlers that already succeeded are recorded in `outbox_event_handled` and are not run again.

**Escalate.** A failure caused by a bug: a backend developer, before the events are retried.
