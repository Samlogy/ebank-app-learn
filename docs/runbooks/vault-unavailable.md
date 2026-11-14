# Vault unavailable or sealed

Alerts: `VaultDown` (no answer), `VaultSealed` (answers, but sealed), `VaultSlowRequests` (p99 above 500 ms). Rules: [vault-alerts.yml](../../infra/observability/prometheus/rules/vault-alerts.yml).

**Meaning.** Vault does not answer, answers `503 sealed`, or answers slowly. Symptoms: a new backend pod stays in `CrashLoopBackOff` with `VaultException` or `Connection refused` to `VAULT_ADDR` in its logs, or ExternalSecrets show `SecretSyncedError`.

**Impact.** Pods already running keep working: the backend loaded its values at startup, and the Secrets that ESO made stay in place. What fails: new backend pods (deploys, restarts, a crash), and changes to values. **With dynamic database credentials** (`vault.database.enabled`), running backends also depend on Vault: their database user must be renewed every hour, so an outage longer than the lease (1 h) stops them too (`EbankDbCredentialsExpiring`).

**Check.**
1. `kubectl -n vault get pods` and `kubectl -n vault exec vault-0 -- vault status`. `Sealed true` means it restarted and needs unsealing; `Seal Type transit` (prod) means it should unseal on its own.
2. ESO: `kubectl -n ebank-<env> get externalsecrets`. The reason is in `kubectl describe externalsecret <name>`.
3. Kubernetes auth (`permission denied` on `auth/kubernetes/login`): the role must match the service account (`ebank-backend` or `ebank-external-secrets`), the namespace `ebank-<env>` and the audience `vault`. Run `infra/vault/k3s/apply-in-cluster.sh <env>` again to restore them.
4. `permission denied` on `ebank/data/...`: the policy no longer covers the path (see [infra/vault/policies](../../infra/vault/policies)).
5. Slow: `vault_core_handle_request` by quantile, the Raft disk (`vault_raft_*`), the audit device (a slow disk slows every request).

**Fix.**
- Sealed, unseal keys (dev, qua): `kubectl -n vault exec -it vault-0 -- vault operator unseal`, with as many key holders as the threshold.
- Sealed, transit auto-unseal (prod): the unseal Vault is unreachable, or the token in the `vault-transit-unseal` Secret expired. Check the pod logs for `seal`; bring the unseal Vault back, or issue a new token (`infra/vault/k3s/unseal/setup-transit.sh prod`) and restart the pod.
- Pod down: check its volume (`kubectl -n vault describe pod vault-0`). The Raft data is on the `data-vault-<n>` PVC.
- Until Vault is back, don't restart the backend or roll out a release.

**Escalate.** Lost unseal keys, a lost transit key, or a corrupt Raft volume: whoever holds the keys and the backups. Without them, the secrets must be recreated.
