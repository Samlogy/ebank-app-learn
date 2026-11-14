# EbankOutboxDeadLetters

Only with the RabbitMQ transport (`EBANK_OUTBOX_RABBITMQ_ENABLED=true`, `rabbitmq.enabled` in the chart).

**Meaning.** A handler failed 5 times on the same event (1 attempt and 4 retries with back-off) and the message moved to its dead-letter queue `ebank.<handler>.dlq`, where `<handler>` is the handler's fully qualified class name (the alert's `handler` label is its simple name).

**Impact.** That handler missed these events: a notification not sent, a transaction missing from analytics. Other handlers of the same event were not affected. The ledger never is.

**Check.**
1. Queues: `kubectl -n ebank-<env> exec statefulset/rabbitmq -- rabbitmqctl list_queues name messages` (compose: `docker compose exec rabbitmq rabbitmqctl list_queues name messages`).
2. Backend logs: the exception of the handler, around the time the alert started.
3. Which events? The message ID is the outbox event ID: `SELECT id, event_type, occurred_at FROM outbox_event WHERE id = '...';` (kept 7 days).

**Fix.** Fix the cause, then move the messages back to the handler's queue with a shovel (the plugin is enabled in [infra/rabbitmq](../../infra/rabbitmq)). It moves what is in the dead-letter queue now, then deletes itself:
```bash
kubectl -n ebank-<env> exec statefulset/rabbitmq -- rabbitmqctl set_parameter shovel ebank-replay \
  '{"src-uri": "amqp://", "src-queue": "ebank.<handler>.dlq", "dest-uri": "amqp://", "dest-queue": "ebank.<handler>", "src-delete-after": "queue-length"}'
```
Redelivered events that the handler already processed are skipped (`outbox_event_handled`).

**Escalate.** A failure caused by a bug: a backend developer, before replaying.
