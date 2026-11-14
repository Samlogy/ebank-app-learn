#!/bin/sh
# Prepares transit auto-unseal on the UNSEAL Vault (a separate Vault, outside the cluster it
# unseals): the transit engine, the key ebank-<env>-unseal, its policy, and a periodic token for
# the Vault servers of <env>. The token goes to stdout only, straight into a Kubernetes Secret:
#
#   VAULT_ADDR=https://vault-unseal.example:8200 VAULT_TOKEN=<admin of the unseal Vault> \
#     infra/vault/k3s/unseal/setup-transit.sh prod |
#     kubectl -n vault create secret generic vault-transit-unseal --from-file=token=/dev/stdin
#
# Safe to run again: the key is never recreated (that would make the cluster unopenable), and
# each run issues a new token; revoke the previous one with `vault token revoke -accessor <id>`.
# The token is periodic (renewed by the Vault servers as long as they run) and orphan (it outlives
# the admin token that created it).
set -eu

ENV=${1:?usage: setup-transit.sh <env>}
HERE=$(cd "$(dirname "$0")" && pwd)
: "${VAULT_ADDR:?VAULT_ADDR (the unseal Vault) is required}"
: "${VAULT_TOKEN:?VAULT_TOKEN (an admin of the unseal Vault) is required}"
export VAULT_ADDR VAULT_TOKEN
KEY=ebank-$ENV-unseal

if ! vault secrets list | grep -q '^transit/'; then
  vault secrets enable transit >/dev/null
  echo "enabled transit" >&2
fi
if vault read "transit/keys/$KEY" >/dev/null 2>&1; then
  echo "key $KEY exists, kept" >&2
else
  # Not exportable and not deletable: losing it means losing the cluster it unseals.
  vault write -f "transit/keys/$KEY" exportable=false allow_plaintext_backup=false >/dev/null
  echo "created key $KEY" >&2
fi
vault write "transit/keys/$KEY/config" deletion_allowed=false >/dev/null
sed "s/__ENV__/$ENV/g" "$HERE/autounseal.hcl" | vault policy write "$KEY" - >/dev/null
echo "policy $KEY" >&2

vault token create -orphan -period=24h -policy="$KEY" -no-default-policy \
  -display-name="vault-$ENV-autounseal" -field=token
