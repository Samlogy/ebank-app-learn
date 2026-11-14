# Vault: config and secrets

Vault is the single source of the settings of every environment, secret or not. Git keeps the non-secret config, so changes are still reviewed in pull requests; it never holds a secret of `qua` or `prod`.

## Layout

One KV v2 mount, `ebank`, with one folder per environment:

```text
ebank/<env>/backend/config     DB_URL, REDIS_HOST, KEYCLOAK_ISSUER_URI, CORS_ALLOWED_ORIGINS, MAIL_*...
ebank/<env>/backend/secrets    DB_USERNAME, DB_PASSWORD, REDIS_PASSWORD, KEYCLOAK_NOTIFICATIONS_CLIENT_SECRET,
                               MAIL_USERNAME, MAIL_PASSWORD, ELASTICSEARCH_USERNAME, ELASTICSEARCH_PASSWORD, ANTHROPIC_API_KEY
ebank/<env>/front-web/config   EBANK_API_ORIGIN, EBANK_WS_ORIGIN, EBANK_KEYCLOAK_ORIGIN, EBANK_KEYCLOAK_REALM, EBANK_KEYCLOAK_CLIENT_ID
ebank/<env>/postgres           POSTGRES_USER, POSTGRES_PASSWORD, KEYCLOAK_DB_PASSWORD
ebank/<env>/redis              REDIS_PASSWORD
ebank/<env>/keycloak           ADMIN_USERNAME, ADMIN_PASSWORD, KC_HOSTNAME
```

`<env>` is `local` (docker compose), `dev`, `qua` or `prod` (k3s). Keys keep the environment variable names that the Spring profiles and the images already use.

| Who | Reads | How |
|---|---|---|
| Backend | `backend/config`, `backend/secrets` | Itself, at startup: Spring Cloud Vault (`spring.config.import`, see [application.yml](../../backend/ebank-app/src/main/resources/application.yml)). Token auth in compose, Kubernetes auth in k3s. |
| PostgreSQL, Redis, Keycloak, front end (nginx) | `postgres`, `redis`, `keycloak`, `front-web/config` | External Secrets Operator copies them into Kubernetes Secrets; Reloader restarts the pods when they change. These images cannot talk to Vault. |
| CI | writes `backend/config`, `front-web/config` | `apply.sh <env> --config-only` before each deploy |

Policies ([policies/](policies)), one per component and environment:

| Policy | Allows |
|---|---|
| `ebank-<env>-backend` | read `ebank/<env>/backend/*` |
| `ebank-<env>-eso` | read `ebank/<env>/{postgres,redis,keycloak,front-web/config}` |
| `ebank-<env>-config-writer` | write `ebank/<env>/{backend,front-web}/config`, read nothing |

So a `qua` pod can't read `prod`, the backend can't read the database admin password of another component, and CI can't read any secret. In k3s, the Kubernetes roles only accept the service accounts `ebank-backend` and `ebank-external-secrets` of the namespace `ebank-<env>`, with tokens issued for the `vault` audience. An audit device logs every access (values are HMAC'd, never in clear).

## Files

| Path | Contents |
|---|---|
| [apply.sh](apply.sh) | Creates the mount, audit, policies (and, with `VAULT_KUBERNETES=true`, the Kubernetes auth and roles), then writes the JSON files of `<env>`. Safe to run again. |
| `local/`, `dev/` | Config **and** dev-only secrets, so the stack starts with no manual step |
| `qua/`, `prod/` | Config only. Their secrets are written by hand (below). |
| [k3s/vault-values.yaml](k3s/vault-values.yaml) | Values for the official Vault chart: one server, Raft storage |
| [k3s/apply-in-cluster.sh](k3s/apply-in-cluster.sh) | Runs `apply.sh` inside the Vault pod, where it can reach the Kubernetes API |

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
vault kv put -mount=ebank prod/keycloak ADMIN_USERNAME=... ADMIN_PASSWORD=... KC_HOSTNAME=https://auth.ebank.example

# CI's token for one environment: config only (store it as the Jenkins credential vault-config-writer-<env>)
vault token create -policy=ebank-prod-config-writer -period=720h -orphan
```

Then deploy the chart: `values-{dev,qua,prod}.yaml` set `configSource: vault`. A Vault pod restart seals it again: it needs the unseal keys (or an auto-unseal, which `prod` should use).

## Changing a value

| What | How | Applied |
|---|---|---|
| Non-secret config | Edit `infra/vault/<env>/*-config.json` in a pull request | CI writes it before the next deploy. By hand: `apply.sh <env> --config-only`. |
| Backend secret | `vault kv patch -mount=ebank <env>/backend/secrets KEY=value` | `kubectl -n ebank-<env> rollout restart deploy/backend`: the backend reads Vault at startup |
| PostgreSQL, Redis, Keycloak or front-end value | `vault kv patch -mount=ebank <env>/<path> KEY=value` | ESO syncs within `vault.refreshInterval` (15 min), Reloader restarts the pods. To hurry: `kubectl annotate externalsecret <name> force-sync=$(date +%s) --overwrite` |

**Rotating a password** that two components share (for example the database password, in `postgres` and `backend/secrets`): change it in PostgreSQL first (`ALTER USER ebank PASSWORD '...'`), then in both Vault paths, then restart the backend. The `POSTGRES_PASSWORD` variable only matters when the volume is created. Dynamic database credentials (planned) will remove this duplication.

## When Vault is down

Running pods keep working: the backend already loaded its values, and ESO leaves the Secrets in place (`deletionPolicy: Retain`). New backend pods fail to start (`fail-fast` in k3s), so a deploy or a crash waits for Vault. See [docs/runbooks/vault-unavailable.md](../../docs/runbooks/vault-unavailable.md).

## Without Vault

`configSource: chart` (the chart's default) keeps the older mode: the `ebank-backend-config` ConfigMap from `backend.config`, and Secrets created by hand or, with `secrets.create: true`, by the chart.
