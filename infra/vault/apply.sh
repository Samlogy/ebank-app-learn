#!/bin/sh
# Applies one environment's Vault layout: the "ebank" KV v2 mount, audit, policies, auth roles, and
# the values kept in git for that environment. Safe to run again: it only creates what is missing
# and overwrites the values from git.
#
#   VAULT_ADDR=... VAULT_TOKEN=... infra/vault/apply.sh <env> [--config-only]
#
#   <env>          local (docker compose), dev, qua or prod (k3s)
#   --config-only  only write the non-secret config (*-config.json): what CI runs on every deploy,
#                  with a token limited to the ebank-<env>-config-writer policy
#
# Environment:
#   VAULT_KUBERNETES=true   also set up the "kubernetes" auth method and the roles of <env>
#                           (run where Vault can reach the Kubernetes API, e.g. in the Vault pod)
#   ANTHROPIC_API_KEY       local only: written into ebank/local/backend/secrets when set
#
# POSIX sh: it also runs inside the hashicorp/vault image, which has no bash or jq.
set -eu

ENV=${1:?usage: apply.sh <local|dev|qua|prod> [--config-only]}
MODE=${2:-}
HERE=$(cd "$(dirname "$0")" && pwd)
: "${VAULT_ADDR:?VAULT_ADDR is required}"
: "${VAULT_TOKEN:?VAULT_TOKEN is required}"
export VAULT_ADDR VAULT_TOKEN

case "$ENV" in local | dev | qua | prod) ;; *) echo "unknown environment: $ENV" >&2; exit 2 ;; esac
[ -d "$HERE/$ENV" ] || { echo "no folder $HERE/$ENV" >&2; exit 2; }

say() { printf '  %s\n' "$*"; }

# File name -> path under the mount: backend-config.json -> <env>/backend/config, postgres.json -> <env>/postgres
path_of() {
  name=$(basename "$1" .json)
  case "$name" in
    *-config) echo "$ENV/${name%-config}/config" ;;
    *-secrets) echo "$ENV/${name%-secrets}/secrets" ;;
    *) echo "$ENV/$name" ;;
  esac
}

if [ "$MODE" != "--config-only" ]; then
  echo "== Vault layout for $ENV"
  if ! vault secrets list | grep -q '^ebank/'; then
    vault secrets enable -path=ebank -version=2 kv >/dev/null
    say "enabled KV v2 at ebank/"
  fi
  # Who read or wrote what, and when. Values are HMAC'd in the log, never written in clear.
  if ! vault audit list 2>/dev/null | grep -q '^stdout/'; then
    vault audit enable -path=stdout file file_path=stdout >/dev/null
    say "enabled the audit log (stdout)"
  fi
  for policy in backend eso config-writer; do
    sed "s/__ENV__/$ENV/g" "$HERE/policies/$policy.hcl" | vault policy write "ebank-$ENV-$policy" - >/dev/null
    say "policy ebank-$ENV-$policy"
  done

  if [ "${VAULT_KUBERNETES:-false}" = "true" ]; then
    if ! vault auth list | grep -q '^kubernetes/'; then
      vault auth enable kubernetes >/dev/null
      say "enabled kubernetes auth"
    fi
    # Inside the cluster Vault finds the API server and its CA on its own.
    vault write auth/kubernetes/config \
      kubernetes_host="https://${KUBERNETES_SERVICE_HOST:?run in the cluster}:${KUBERNETES_SERVICE_PORT:-443}" >/dev/null
    vault write "auth/kubernetes/role/ebank-$ENV-backend" \
      bound_service_account_names=ebank-backend bound_service_account_namespaces="ebank-$ENV" \
      policies="ebank-$ENV-backend" audience=vault ttl=1h >/dev/null
    vault write "auth/kubernetes/role/ebank-$ENV-eso" \
      bound_service_account_names=ebank-external-secrets bound_service_account_namespaces="ebank-$ENV" \
      policies="ebank-$ENV-eso" audience=vault ttl=1h >/dev/null
    # audience=vault: only service account tokens issued for Vault are accepted, not the pods'
    # default tokens for the Kubernetes API.
    say "kubernetes roles ebank-$ENV-backend and ebank-$ENV-eso (namespace ebank-$ENV, audience vault)"
  fi

  # docker compose: the backend container uses a fixed token, limited by its policy.
  if [ "$ENV" = "local" ] && ! vault token lookup local-backend-token >/dev/null 2>&1; then
    # A fixed ID is weaker (Vault warns about SHA1 lookups): acceptable for a local, in-memory dev server only.
    vault token create -id=local-backend-token -policy=ebank-local-backend -orphan -period=768h >/dev/null 2>&1
    say "token local-backend-token (policy ebank-local-backend)"
  fi
fi

echo "== Values for $ENV"
for file in "$HERE/$ENV"/*.json; do
  case "$file" in
    *-config.json) ;;
    *) [ "$MODE" = "--config-only" ] && continue ;;
  esac
  path=$(path_of "$file")
  vault kv put -mount=ebank "$path" "@$file" >/dev/null
  say "ebank/$path <- ${file#"$HERE"/}"
done

if [ "$ENV" = "local" ] && [ -n "${ANTHROPIC_API_KEY:-}" ] && [ "$MODE" != "--config-only" ]; then
  vault kv patch -mount=ebank local/backend/secrets ANTHROPIC_API_KEY="$ANTHROPIC_API_KEY" >/dev/null
  say "ebank/local/backend/secrets: ANTHROPIC_API_KEY from the environment"
fi
