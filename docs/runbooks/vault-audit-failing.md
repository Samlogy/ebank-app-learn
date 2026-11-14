# VaultAuditLogFailing

**Meaning.** Vault could not write a request or a response to its audit device (`stdout`, enabled by [apply.sh](../../infra/vault/apply.sh)).

**Impact.** Vault refuses every request it cannot log to at least one audit device: logins, reads and writes fail. That is on purpose: nothing happens without a trace.

**Check.**
1. Vault's logs: `kubectl -n vault logs vault-0 --since=15m | grep -i audit`.
2. `vault audit list -detailed` (with an admin token): which device, which path.
3. stdout blocked: a log collector that stopped reading can block the container's output.

**Fix.** Restore the device's output (restart the stuck collector, free the disk). If the device cannot be fixed quickly, add a second one first, then disable the broken one: `vault audit enable -path=file2 file file_path=/vault/audit/audit.log` (needs a writable volume, `server.auditStorage.enabled` in the chart).

**Escalate.** Disabling the only audit device: the security owner decides, since Vault then runs without a trace.
