# Runbooks

One page per Prometheus alert in [infra/observability/prometheus/rules](../../infra/observability/prometheus/rules) (`ebank-alerts.yml`, `ebank-slo.yml`, `vault-alerts.yml`). Each alert links here through its `runbook_url` annotation, so the page opens from the alert email, Alertmanager or Grafana.

| Alert | Severity | Page |
|---|---|---|
| `EbankBackendDown` | critical | [ebank-backend-down.md](ebank-backend-down.md) |
| `EbankAvailabilityBudgetBurn` | critical, warning | [ebank-availability-budget-burn.md](ebank-availability-budget-burn.md) |
| `EbankLatencyBudgetBurn` | critical, warning | [ebank-latency-budget-burn.md](ebank-latency-budget-burn.md) |
| `EbankHeapAlmostFull` | warning | [ebank-heap-almost-full.md](ebank-heap-almost-full.md) |
| `EbankAuthRejectionSpike` | warning | [ebank-auth-rejection-spike.md](ebank-auth-rejection-spike.md) |
| `EbankOutboxBacklog` | warning | [ebank-outbox-backlog.md](ebank-outbox-backlog.md) |
| `EbankOutboxEventsFailed` | warning | [ebank-outbox-events-failed.md](ebank-outbox-events-failed.md) |
| `EbankOutboxDeadLetters` | warning | [ebank-outbox-dead-letters.md](ebank-outbox-dead-letters.md) |
| `EbankNotificationDeliveryFailing` | warning | [ebank-notification-delivery-failing.md](ebank-notification-delivery-failing.md) |
| `EbankAssistantUnavailable` | warning | [ebank-assistant-unavailable.md](ebank-assistant-unavailable.md) |
| `EbankAdvisorQueueSlow` | warning | [ebank-advisor-queue-slow.md](ebank-advisor-queue-slow.md) |
| `EbankDbCredentialsExpiring`, `EbankDbCredentialsRotationFailing` | critical, warning | [ebank-db-credentials.md](ebank-db-credentials.md) |
| `VaultDown`, `VaultSealed`, `VaultSlowRequests` | critical, warning | [vault-unavailable.md](vault-unavailable.md) |
| `VaultNoActiveNode`, `VaultRaftUnhealthy` | critical, warning | [vault-raft-unhealthy.md](vault-raft-unhealthy.md) |
| `VaultAuditLogFailing` | critical | [vault-audit-failing.md](vault-audit-failing.md) |
| `VaultLeaseCountHigh` | warning | [vault-lease-count-high.md](vault-lease-count-high.md) |
| `ExternalSecretSyncFailing` | warning | [external-secret-sync-failing.md](external-secret-sync-failing.md) |

## Commands used in the pages

| What | docker compose (`infra/compose`) | k3s |
|---|---|---|
| Backend logs | `docker compose logs --since 15m backend` | `kubectl -n ebank-<env> logs deploy/backend --since=15m` |
| Restart the backend | `docker compose restart backend` | `kubectl -n ebank-<env> rollout restart deploy/backend` |
| SQL | `docker compose exec postgres psql -U ebank -d ebank` | `kubectl -n ebank-<env> exec -it statefulset/postgres -- psql -U ebank -d ebank` |
| Metrics | http://localhost:9090 | the monitoring namespace's Prometheus |
| Traces | http://localhost:16686 (Jaeger) | the cluster's tracing backend |

Logs are ECS JSON outside `dev`: filter on `log.level` and follow `trace.id` into Jaeger.

## Writing a page

Keep the same five sections: **Meaning**, **Impact**, **Check**, **Fix**, **Escalate**. Write commands that can be copied as they are. When a fix needs a decision, say who takes it.
