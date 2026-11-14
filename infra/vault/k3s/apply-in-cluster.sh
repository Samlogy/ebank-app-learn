#!/usr/bin/env bash
# Runs infra/vault/apply.sh inside the Vault pod, where Vault can reach the Kubernetes API, so the
# "kubernetes" auth method and the roles of <env> are set up too.
#
#   VAULT_TOKEN=<admin token> infra/vault/k3s/apply-in-cluster.sh <dev|qua|prod> [--config-only]
#
# Uses the current kubectl context. The token goes through stdin, never on a command line.
set -euo pipefail

ENV=${1:?usage: apply-in-cluster.sh <dev|qua|prod> [--config-only]}
MODE=${2:-}
: "${VAULT_TOKEN:?VAULT_TOKEN (an admin token) is required}"
NS=${VAULT_NAMESPACE:-vault}
POD=${VAULT_POD:-vault-0}
HERE=$(cd "$(dirname "$0")/.." && pwd)
REMOTE=/tmp/ebank-vault

# Only the policies, apply.sh and this environment's values are copied.
tar -C "$HERE" -cf - apply.sh policies "$ENV" |
  kubectl -n "$NS" exec -i "$POD" -- sh -c "rm -rf $REMOTE && mkdir -p $REMOTE && tar -C $REMOTE -xf -"
printf '%s\n' "$VAULT_TOKEN" |
  kubectl -n "$NS" exec -i "$POD" -- sh -c \
    "read -r VAULT_TOKEN; export VAULT_TOKEN VAULT_ADDR=http://127.0.0.1:8200 VAULT_KUBERNETES=true; sh $REMOTE/apply.sh $ENV $MODE; rc=\$?; rm -rf $REMOTE; exit \$rc"
