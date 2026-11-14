# ExternalSecretSyncFailing

**Meaning.** External Secrets Operator could not refresh a Secret from Vault for 15 minutes (`ebank-postgres`, `ebank-redis`, `ebank-keycloak`, `ebank-front-web-config`, `ebank-rabbitmq`).

**Impact.** The Secret keeps its last values (`deletionPolicy: Retain`), so running pods are fine. A change written in Vault does not reach the pods.

**Check.** `kubectl -n <namespace> describe externalsecret <name>`, then the events:
- `permission denied`: the `ebank-<env>-eso` policy or role (run `infra/vault/k3s/apply-in-cluster.sh <env>`).
- `secret not found`: the path is missing in Vault (`vault kv get -mount=ebank <env>/<path>`).
- Vault unreachable or sealed: [vault-unavailable.md](vault-unavailable.md).

**Fix.** Correct the cause, then force a sync: `kubectl -n <namespace> annotate externalsecret <name> force-sync=$(date +%s) --overwrite`.

**Escalate.** The ESO controller itself failing (`kubectl -n external-secrets logs deploy/external-secrets`): whoever runs the cluster.
