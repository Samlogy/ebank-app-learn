# EbankAdvisorQueueSlow

**Meaning.** Over 30 minutes, customers waited more than 10 minutes on average before an advisor took their conversation.

**Impact.** Customers who asked for a human are kept waiting.

**Check.**
1. Are advisors online? An advisor counts as online only while subscribed to `/user/queue/chat`. Without anyone online, conversations stay queued and advisors get an email at most every 5 minutes.
2. Queue size: `SELECT count(*), min(handoff_at) FROM conversation WHERE state = 'WAITING_ADVISOR';`
3. Advisor alert emails missing: the `ebank-notifications` service account needs `view-realm` to list advisors.

**Fix.** Ask more advisors to connect, or tell customers about the delay. Nothing technical needs fixing unless the alert emails are missing.

**Escalate.** Staffing: the support team lead.
