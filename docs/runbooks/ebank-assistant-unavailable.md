# EbankAssistantUnavailable

**Meaning.** The chat assistant failed to answer more than 3 times in 10 minutes (`outcome` `unavailable` or `failed`).

**Impact.** Customers get the fallback message and can still ask for an advisor. Banking features are unaffected.

**Check.**
1. Logs of `AssistantRunner`: authentication errors (bad or revoked `ANTHROPIC_API_KEY`), `429` (rate limit or quota), `529` or `5xx` (provider overloaded), timeouts.
2. Latency: `ebank_chatbot_llm_latency_seconds`. A slow provider shows up there before failures.
3. The provider's status page.

**Fix.**
- Key: rotate `ANTHROPIC_API_KEY` in the backend secrets, then restart the backend.
- Quota: raise it with the provider, or wait for it to reset.
- Provider outage: nothing to do but tell the advisors to expect more handoffs.

**Escalate.** Billing or quota decisions: the project owner.
