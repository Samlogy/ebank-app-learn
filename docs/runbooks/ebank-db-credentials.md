# EbankDbCredentialsExpiring, EbankDbCredentialsRotationFailing

Only with dynamic database credentials (`vault.database.enabled`, `VAULT_DATABASE_ENABLED=true`).

**Meaning.** The backend's database user comes from Vault (`database/creds/ebank-<env>-backend`). Its lease is renewed every hour and, after 24 h, replaced by a new user a few seconds before it ends. `EbankDbCredentialsRotationFailing` (warning): a renewal or a replacement failed. `EbankDbCredentialsExpiring` (critical): less than a minute left on the lease for 3 minutes, so the replacement is not coming.

**Impact.** Warning: none yet, the current user works until its lease ends. Critical: when it ends, Vault drops the user and closes its sessions; every query fails until the backend restarts with a new one.

**Check.**
1. Backend logs: `Vault database credentials:` lines give Vault's answer (`permission denied`, connection refused...).
2. Vault itself: [vault-unavailable.md](vault-unavailable.md).
3. `permission denied` on `database/creds/...`: the backend policy (`policies/backend.hcl`) or the role; `apply-in-cluster.sh <env>` restores the policy, `enable-dynamic-db.sh <env>` the role.
4. Vault cannot reach PostgreSQL: `vault read database/config/ebank-<env>` (admin token), the `postgres-from-vault` NetworkPolicy, PostgreSQL's logs.

**Fix.** Once Vault answers again, restart the backend if its user already expired: `kubectl -n ebank-<env> rollout restart deploy/backend`. It gets a new user at startup.

**Escalate.** Vault's connection to PostgreSQL broken (its `vault_admin` password is only known to Vault): run `infra/vault/k3s/enable-dynamic-db.sh <env>` again, which resets it. To go back to the static user while investigating, deploy with `vault.database.enabled: false` (the static `DB_USERNAME`/`DB_PASSWORD` of `backend/secrets` are used again).
