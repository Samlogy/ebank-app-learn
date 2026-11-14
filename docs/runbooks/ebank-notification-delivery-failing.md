# EbankNotificationDeliveryFailing

**Meaning.** More than 5 notification deliveries failed in 15 minutes (`ebank_notifications_delivered_total{outcome="failed"}`).

**Impact.** Users are not told about transfers: no live push, no email or SMS. The notifications stay in the app's list.

**Check.** Which channel? `sum by (channel) (increase(ebank_notifications_delivered_total{outcome="failed"}[15m]))`
- `live`: the WebSocket push failed. Look at `ebank_websocket_sessions` and the backend logs.
- `email`: SMTP is unreachable or refuses the login. Check `MAIL_HOST`, `MAIL_PORT` and the credentials. Locally, is Mailpit up?
- Lookups of the user's email or phone: the `ebank-notifications` service account in Keycloak needs `view-users` (and `kc-admin-api` from Keycloak 26.8). An empty `KEYCLOAK_NOTIFICATIONS_CLIENT_SECRET` turns the fallback off.
- `sms`: SMS is a logging stub for now; failures there are bugs.

**Fix.** Restore the channel. Fallback deliveries are outbox events, so they are retried; see [ebank-outbox-backlog.md](ebank-outbox-backlog.md).

**Escalate.** SMTP provider problems: whoever owns the mail account.
