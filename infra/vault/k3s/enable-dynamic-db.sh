#!/usr/bin/env bash
# Turns on dynamic PostgreSQL credentials for one environment (infra/vault/README.md, "Dynamic
# database credentials"). Safe to run again, for example to reset Vault's connection.
#
#   VAULT_TOKEN=<admin token> infra/vault/k3s/enable-dynamic-db.sh <dev|qua|prod>
#
#   1. PostgreSQL (database/setup.sql, as POSTGRES_USER): roles ebank_app and vault_admin, and the
#      schema objects handed over to ebank_app.
#   2. Vault: the "database" engine, the connection ebank-<env> as vault_admin and the role
#      ebank-<env>-backend (database/backend-role.json), then rotate-root: from now on only Vault
#      knows vault_admin's password.
#   3. A test: credentials issued, then revoked at once.
# Then deploy with vault.database.enabled=true (values-<env>.yaml): the backend asks Vault for its
# own database user at startup. DB_USERNAME / DB_PASSWORD in ebank/<env>/backend/secrets are then
# ignored; keep them while you may still need to switch back.
#
# Uses the current kubectl context. Secrets only go through stdin, never on a command line.
set -euo pipefail

ENV=${1:?usage: enable-dynamic-db.sh <dev|qua|prod>}
: "${VAULT_TOKEN:?VAULT_TOKEN (an admin token) is required}"
NS=ebank-$ENV
VAULT_NS=${VAULT_NAMESPACE:-vault}
VAULT_POD=${VAULT_POD:-vault-0}
HERE=$(cd "$(dirname "$0")/.." && pwd)

# Runs a shell script in the Vault pod. Its stdin: the admin token on the first line, then the
# payload for `vault write ... -`.
in_vault() {
  kubectl -n "$VAULT_NS" exec -i "$VAULT_POD" -- sh -c \
    "read -r VAULT_TOKEN; export VAULT_TOKEN VAULT_ADDR=http://127.0.0.1:8200; $1"
}

# Hex only: safe in SQL, JSON and a connection URL. Short-lived: rotate-root replaces it below.
admin_password=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')

echo "== PostgreSQL ($NS): roles ebank_app and vault_admin"
kubectl -n "$NS" exec -i statefulset/postgres -- sh -c 'cat > /tmp/ebank-dynamic-db.sql' < "$HERE/database/setup.sql"
# shellcheck disable=SC2016  # expanded inside the pod, on purpose
printf '%s\n' "$admin_password" | kubectl -n "$NS" exec -i statefulset/postgres -- sh -c \
  'read -r VAULT_ADMIN_PASSWORD; export VAULT_ADMIN_PASSWORD
   psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d ebank -f /tmp/ebank-dynamic-db.sql; rc=$?
   rm -f /tmp/ebank-dynamic-db.sql; exit $rc'

echo "== Vault: database engine, connection ebank-$ENV, role ebank-$ENV-backend"
printf '%s\n' "$VAULT_TOKEN" | in_vault \
  'vault secrets list | grep -q "^database/" || vault secrets enable database >/dev/null'
{
  printf '%s\n' "$VAULT_TOKEN"
  # In-cluster PostgreSQL has no TLS, like the backend's own connection (DB_URL).
  cat <<JSON
{
  "plugin_name": "postgresql-database-plugin",
  "connection_url": "postgresql://{{username}}:{{password}}@postgres.$NS.svc:5432/ebank?sslmode=disable",
  "username": "vault_admin",
  "password": "$admin_password",
  "password_authentication": "scram-sha-256",
  "allowed_roles": "ebank-$ENV-backend",
  "username_template": "v-{{.RoleName | truncate 24}}-{{random 12 | lowercase}}-{{unix_time}}"
}
JSON
} | in_vault "vault write database/config/ebank-$ENV - >/dev/null"
{ printf '%s\n' "$VAULT_TOKEN"; sed "s/__ENV__/$ENV/g" "$HERE/database/backend-role.json"; } |
  in_vault "vault write database/roles/ebank-$ENV-backend - >/dev/null"
printf '%s\n' "$VAULT_TOKEN" | in_vault "vault write -f database/rotate-root/ebank-$ENV >/dev/null"
unset admin_password
echo "  vault_admin's password rotated by Vault"

echo "== Test"
printf '%s\n' "$VAULT_TOKEN" | in_vault \
  "lease=\$(vault read -field=lease_id database/creds/ebank-$ENV-backend) && vault lease revoke \"\$lease\" >/dev/null && echo '  credentials issued and revoked'"
echo "Next: set vault.database.enabled: true in infra/helm/ebank/values-$ENV.yaml and deploy."
