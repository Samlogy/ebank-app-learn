# Vault unavailable or sealed

**Meaning.** Vault does not answer, or answers `503 sealed`. Symptoms: a new backend pod stays in `CrashLoopBackOff` with `VaultException` or `Connection refused` to `VAULT_ADDR` in its logs, or ExternalSecrets show `SecretSyncedError`.

**Impact.** Pods already running keep working: the backend loaded its values at startup, and the Secrets that ESO made stay in place. What fails: new backend pods (deploys, restarts, a crash), and changes to values.

**Check.**
1. `kubectl -n vault get pods` and `kubectl -n vault exec vault-0 -- vault status`. `Sealed true` means it restarted and needs unsealing.
2. ESO: `kubectl -n ebank-<env> get externalsecrets`. The reason is in `kubectl describe externalsecret <name>`.
3. Kubernetes auth (`permission denied` on `auth/kubernetes/login`): the role must match the service account (`ebank-backend` or `ebank-external-secrets`), the namespace `ebank-<env>` and the audience `vault`. Run `infra/vault/k3s/apply-in-cluster.sh <env>` again to restore them.
4. `permission denied` on `ebank/data/...`: the policy no longer covers the path (see [infra/vault/policies](../../infra/vault/policies)).

**Fix.**
- Sealed: `kubectl -n vault exec -it vault-0 -- vault operator unseal`, with as many key holders as the threshold.
- Pod down: check its volume (`kubectl -n vault describe pod vault-0`). The Raft data is on the `data-vault-0` PVC.
- Until Vault is back, don't restart the backend or roll out a release.

**Escalate.** Lost unseal keys or a corrupt Raft volume: whoever holds the keys and the backups. Without them, the secrets must be recreated.
