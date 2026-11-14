# Vault: config and secrets

Vault is the single source of the settings of every environment, secret or not. Git keeps the non-secret config, so changes are still reviewed in pull requests; it never holds a secret of `qua` or `prod`. Why Spring Cloud Vault for the backend and External Secrets Operator for the rest: [ADR 0001](../../docs/adr/0001-vault-single-source.md).

## Layout

One KV v2 mount, `ebank`, with one folder per environment:

```text
ebank/<env>/backend/config     DB_URL, REDIS_HOST, KEYCLOAK_ISSUER_URI, CORS_ALLOWED_ORIGINS, MAIL_*...
ebank/<env>/backend/secrets    DB_USERNAME, DB_PASSWORD, REDIS_PASSWORD, KEYCLOAK_NOTIFICATIONS_CLIENT_SECRET,
                               MAIL_USERNAME, MAIL_PASSWORD, ELASTICSEARCH_USERNAME, ELASTICSEARCH_PASSWORD, ANTHROPIC_API_KEY
ebank/<env>/front-web/config   EBANK_API_ORIGIN, EBANK_WS_ORIGIN, EBANK_KEYCLOAK_ORIGIN, EBANK_KEYCLOAK_REALM, EBANK_KEYCLOAK_CLIENT_ID
ebank/<env>/postgres           POSTGRES_USER, POSTGRES_PASSWORD, KEYCLOAK_DB_PASSWORD
ebank/<env>/redis              REDIS_PASSWORD
ebank/<env>/keycloak           ADMIN_USERNAME, ADMIN_PASSWORD, KEYCLOAK_NOTIFICATIONS_CLIENT_SECRET (for the realm)
ebank/<env>/keycloak/config    KC_HOSTNAME, KEYCLOAK_SMTP_HOST, KEYCLOAK_SMTP_PORT, KEYCLOAK_SMTP_FROM
ebank/<env>/rabbitmq           RABBITMQ_DEFAULT_USER, RABBITMQ_DEFAULT_PASS (only with rabbitmq.enabled)
database/creds/ebank-<env>-backend   dynamic PostgreSQL users (only after enable-dynamic-db.sh)
```

`backend/secrets` also holds `RABBITMQ_USERNAME` and `RABBITMQ_PASSWORD` when the outbox goes through RabbitMQ.

`<env>` is `local` (docker compose), `dev`, `qua` or `prod` (k3s). Keys keep the environment variable names that the Spring profiles and the images already use.

| Who | Reads | How |
|---|---|---|
| Backend | `backend/config`, `backend/secrets` | Itself, at startup: Spring Cloud Vault (`spring.config.import`, see [application.yml](../../backend/ebank-app/src/main/resources/application.yml)). Token auth in compose, Kubernetes auth in k3s. |
| PostgreSQL, Redis, Keycloak and its realm Job, front end (nginx) | `postgres`, `redis`, `keycloak`, `keycloak/config`, `front-web/config` | External Secrets Operator copies them into Kubernetes Secrets; Reloader restarts the pods when they change. These images cannot talk to Vault. |
| CI | writes `backend/config`, `front-web/config`, `keycloak/config` | AppRole `ebank-<env>-ci`, then `apply.sh <env> --config-only` before each deploy ([infra/ci/deploy.sh](../ci/deploy.sh)) |

Policies ([policies/](policies)), one per component and environment:

| Policy | Allows |
|---|---|
| `ebank-<env>-backend` | read `ebank/<env>/backend/*`; read `database/creds/ebank-<env>-backend` and revoke its own leases |
| `ebank-<env>-eso` | read `ebank/<env>/{postgres,redis,keycloak,keycloak/config,front-web/config,rabbitmq}` |
| `ebank-<env>-config-writer` | write `ebank/<env>/{backend,front-web,keycloak}/config`, read nothing. Given to CI through AppRole `ebank-<env>-ci`: tokens live 10 minutes, the secret ID 90 days. |

So a `qua` pod can't read `prod`, the backend can't read the database admin password of another component, and CI can't read any secret. In k3s, the Kubernetes roles only accept the service accounts `ebank-backend` and `ebank-external-secrets` of the namespace `ebank-<env>`, with tokens issued for the `vault` audience. An audit device logs every access (values are HMAC'd, never in clear).

## Files

| Path | Contents |
|---|---|
| [apply.sh](apply.sh) | Creates the mount, audit, policies (and, with `VAULT_KUBERNETES=true`, the Kubernetes auth and roles), then writes the JSON files of `<env>`. Safe to run again. |
| `local/`, `dev/` | Config **and** dev-only secrets, so the stack starts with no manual step |
| `qua/`, `prod/` | Config only. Their secrets are written by hand (below). |
| [k3s/vault-values.yaml](k3s/vault-values.yaml) | Values for the official Vault chart: one server, Raft storage, metrics |
| [k3s/vault-values-ha.yaml](k3s/vault-values-ha.yaml) | prod, on top of the above: three servers, transit auto-unseal |
| [k3s/unseal/](k3s/unseal) | `setup-transit.sh`: the transit key and token on the unseal Vault |
| [k3s/apply-in-cluster.sh](k3s/apply-in-cluster.sh) | Runs `apply.sh` inside the Vault pod, where it can reach the Kubernetes API |
| [k3s/enable-dynamic-db.sh](k3s/enable-dynamic-db.sh), [database/](database) | Dynamic PostgreSQL credentials: database roles, Vault's connection and role |

## docker compose

Nothing to do: `vault` starts in dev mode (in memory, root token `dev-root-token`, UI on http://localhost:8200), and `vault-seed` runs `apply.sh local` on every start. The backend container only gets `VAULT_ADDR`, `VAULT_TOKEN=local-backend-token` (policy `ebank-local-backend`) and `EBANK_ENV=local`.

```bash
export VAULT_ADDR=http://localhost:8200 VAULT_TOKEN=dev-root-token
vault kv get -mount=ebank local/backend/config
vault kv patch -mount=ebank local/backend/secrets ANTHROPIC_API_KEY=sk-...   # or set ANTHROPIC_API_KEY before `docker compose up`
docker compose restart backend                                                # values are read at startup
```

A backend started on the host (`./mvnw spring-boot:run`) keeps `VAULT_ENABLED=false` and its `localhost` defaults: the `local` values use compose host names.

## k3s: first install

```bash
helm repo add hashicorp https://helm.releases.hashicorp.com
helm repo add external-secrets https://charts.external-secrets.io
helm repo add stakater https://stakater.github.io/stakater-charts

helm upgrade --install vault hashicorp/vault --version 0.34.1 -n vault --create-namespace -f infra/vault/k3s/vault-values.yaml
helm upgrade --install external-secrets external-secrets/external-secrets --version 2.15.0 -n external-secrets --create-namespace --wait
helm upgrade --install reloader stakater/reloader --version 2.2.20 -n reloader --create-namespace --wait

# Once, by a person: keep the unseal keys and the root token offline (password manager, split between people).
kubectl -n vault exec -it vault-0 -- vault operator init -key-shares=5 -key-threshold=3
kubectl -n vault exec -it vault-0 -- vault operator unseal      # 3 times, with 3 different keys

# Layout, Kubernetes auth and the values from git, per environment
VAULT_TOKEN=<admin token> infra/vault/k3s/apply-in-cluster.sh dev

# qua and prod secrets: typed by a person, never stored in git
vault kv put -mount=ebank prod/backend/secrets DB_USERNAME=... DB_PASSWORD=... REDIS_PASSWORD=... \
  KEYCLOAK_NOTIFICATIONS_CLIENT_SECRET=... MAIL_USERNAME=... MAIL_PASSWORD=... \
  ELASTICSEARCH_USERNAME=... ELASTICSEARCH_PASSWORD=... ANTHROPIC_API_KEY=...
vault kv put -mount=ebank prod/postgres POSTGRES_USER=... POSTGRES_PASSWORD=... KEYCLOAK_DB_PASSWORD=...
vault kv put -mount=ebank prod/redis REDIS_PASSWORD=...
vault kv put -mount=ebank prod/keycloak ADMIN_USERNAME=... ADMIN_PASSWORD=... KEYCLOAK_NOTIFICATIONS_CLIENT_SECRET=...
# Only with rabbitmq.enabled (and RABBITMQ_USERNAME / RABBITMQ_PASSWORD, the same user, in prod/backend/secrets)
vault kv put -mount=ebank prod/rabbitmq RABBITMQ_DEFAULT_USER=... RABBITMQ_DEFAULT_PASS=...

# CI's AppRole for one environment: config only. Store both as the Jenkins username/password
# credential vault-approle-<env> (username = role ID, password = secret ID). Renew the secret ID
# before 90 days, and revoke the old one: vault write auth/approle/role/ebank-prod-ci/secret-id-accessor/destroy ...
vault read -field=role_id auth/approle/role/ebank-prod-ci/role-id
vault write -f -field=secret_id auth/approle/role/ebank-prod-ci/secret-id
```

Then deploy the chart: `values-{dev,qua,prod}.yaml` set `configSource: vault`. A Vault pod restart seals it again: it needs the unseal keys, or the auto-unseal of `prod` (below).

## Metrics and alerts

Both values files turn on Vault's telemetry: `/v1/sys/metrics?format=prometheus`, without a token (the metrics carry no secret and the endpoint is only reachable inside the cluster). Once kube-prometheus-stack is installed ([infra/observability/k8s](../observability/k8s)), add `--set serverTelemetry.serviceMonitor.enabled=true` to the Vault install: every pod is scraped, standbys included, and a sealed Vault still reports `vault_core_unsealed 0`. Compose exposes the same endpoint on `vault:8202` for its Prometheus.

The alerts are in [vault-alerts.yml](../observability/prometheus/rules/vault-alerts.yml): `VaultDown`, `VaultSealed`, `VaultNoActiveNode`, `VaultAuditLogFailing` (Vault refuses requests it cannot audit), `VaultRaftUnhealthy` (three servers only), `VaultLeaseCountHigh`, `VaultSlowRequests`, and `ExternalSecretSyncFailing`. Each has a runbook in [docs/runbooks](../../docs/runbooks).

## prod: HA and auto-unseal

`prod` runs three servers in one Raft cluster, so one can be lost (or drained) without an outage, and unseals itself after a restart: no key holder has to be woken up. The root key is encrypted by a **transit key on a separate Vault** (the unseal Vault, outside the prod cluster). A cloud KMS works the same way (`seal "awskms"`, `"gcpckms"`, `"azurekeyvault"` instead of `seal "transit"`).

```bash
# 1. On the unseal Vault: transit engine, key ebank-prod-unseal (not exportable, not deletable),
#    its policy, and a periodic token for the prod servers, straight into a Secret.
VAULT_ADDR=https://vault-unseal.example:8200 VAULT_TOKEN=<admin of the unseal Vault> \
  infra/vault/k3s/unseal/setup-transit.sh prod |
  kubectl -n vault create secret generic vault-transit-unseal --from-file=token=/dev/stdin

# 2. Set the unseal Vault's address in vault-values-ha.yaml (seal "transit"), then install.
helm upgrade --install vault hashicorp/vault --version 0.34.1 -n vault --create-namespace \
  -f infra/vault/k3s/vault-values.yaml -f infra/vault/k3s/vault-values-ha.yaml

# 3a. New cluster: init once. No unseal keys any more, recovery keys instead (needed for a few
#     operations such as generating a new root token): keep them offline, split between people.
kubectl -n vault exec -it vault-0 -- vault operator init -recovery-shares=5 -recovery-threshold=3
# vault-1 and vault-2 join on their own (retry_join) and unseal with the transit key.

# 3b. Existing single server with unseal keys: after the upgrade, migrate the seal once,
#     with the old unseal keys (they become the recovery keys).
kubectl -n vault exec -it vault-0 -- vault operator unseal -migrate    # 3 times, 3 different keys
```

The prod nodes need room for three servers on three different nodes (the chart's anti-affinity), and the unseal Vault must not depend on the prod cluster, or the two could lock each other out. This was checked on three Docker containers: init with recovery keys, three voters after autopilot, a restarted server unseals on its own, a standby serves its own metrics, autopilot reports no failure tolerance when a server stops, and `unseal -migrate` keeps the data.

## Dynamic database credentials

Without them, the backend logs in to PostgreSQL with the user of `backend/secrets`, which never changes and is usually `POSTGRES_USER`, the database superuser. With them, Vault creates **a database user per backend start**, valid one hour, renewed while the backend runs and replaced after 24 hours; it drops the user when the lease ends or the backend stops. A leaked password is short-lived, and the backend no longer runs as superuser.

```text
ebank_app     NOLOGIN, owns the schema. Each Vault user is a member and switches to it at login
              (ALTER ROLE ... SET role), so Flyway's new tables are owned by ebank_app too.
vault_admin   LOGIN, CREATEROLE, ADMIN of ebank_app: what Vault uses. Its password is rotated by
              Vault right after the setup (rotate-root): nobody knows it.
v-ebank-<env>-backend-...   created by Vault (database/backend-role.json), dropped at the end of the lease.
```

```bash
VAULT_TOKEN=<admin token> infra/vault/k3s/enable-dynamic-db.sh <env>   # database/setup.sql, Vault's connection and role, a test
# then vault.database.enabled: true in values-<env>.yaml, and deploy
```

The script is safe to run again: it hands any new objects over to `ebank_app` and resets Vault's connection. The backend reads the credentials as `VAULT_DB_USERNAME` and `VAULT_DB_PASSWORD`, which the profiles prefer, so the static user left in `backend/secrets` is ignored and remains a way back (`vault.database.enabled: false`). `VaultDatabaseCredentials` moves the connection pool to each new user without a restart; it is covered by `DynamicDatabaseCredentialsIT`, with the files of this folder and lifetimes of seconds.

The price: running backends now need Vault every hour, not only at startup. A Vault outage longer than the lease stops them (`EbankDbCredentialsExpiring`). Keycloak keeps its static user.

## Changing a value

| What | How | Applied |
|---|---|---|
| Non-secret config | Edit `infra/vault/<env>/*-config.json` in a pull request | CI writes it before the next deploy. By hand: `apply.sh <env> --config-only`. |
| Backend secret | `vault kv patch -mount=ebank <env>/backend/secrets KEY=value` | `kubectl -n ebank-<env> rollout restart deploy/backend`: the backend reads Vault at startup |
| PostgreSQL, Redis, Keycloak or front-end value | `vault kv patch -mount=ebank <env>/<path> KEY=value` | ESO syncs within `vault.refreshInterval` (15 min), Reloader restarts the pods. To hurry: `kubectl annotate externalsecret <name> force-sync=$(date +%s) --overwrite` |

**Rotating a password** that two components share (for example the database password, in `postgres` and `backend/secrets`): change it in PostgreSQL first (`ALTER USER ebank PASSWORD '...'`), then in both Vault paths, then restart the backend. The `POSTGRES_PASSWORD` variable only matters when the volume is created. Dynamic database credentials (planned) will remove this duplication. The same goes for `KEYCLOAK_NOTIFICATIONS_CLIENT_SECRET` (in `backend/secrets` and `keycloak`): change both, redeploy (the `keycloak-config` Job sets it on the client), then the backend restarts with it.

## When Vault is down

Running pods keep working: the backend already loaded its values, and ESO leaves the Secrets in place (`deletionPolicy: Retain`). New backend pods fail to start (`fail-fast` in k3s), so a deploy or a crash waits for Vault. With dynamic database credentials, running backends also stop once their database lease ends (at most an hour). See [docs/runbooks/vault-unavailable.md](../../docs/runbooks/vault-unavailable.md).

## Without Vault

`configSource: chart` (the chart's default) keeps the older mode: the `ebank-backend-config` ConfigMap from `backend.config`, and Secrets created by hand or, with `secrets.create: true`, by the chart.
