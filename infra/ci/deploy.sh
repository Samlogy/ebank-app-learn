#!/usr/bin/env bash
# Deploys one environment, the way CI does it:
#   1. log in to Vault with AppRole and write the environment's non-secret config from git,
#   2. helm upgrade --atomic (a release whose pods never get ready is rolled back by Helm),
#   3. smoke test; if it fails, roll back to the previous release and fail.
#
#   IMAGE_TAG=<git sha> VAULT_ROLE_ID=... VAULT_SECRET_ID=... infra/ci/deploy.sh <dev|qua|prod>
#
# Environment:
#   IMAGE_TAG                    required: the image tag built once and promoted unchanged
#   REGISTRY                     default docker.io/ebank
#   VAULT_ROLE_ID, VAULT_SECRET_ID   AppRole ebank-<env>-ci (or VAULT_TOKEN directly)
#   VAULT_SERVICE                default vault/vault: namespace/service reached by port-forward
#   SMOKE_URL                    optional: also check the front end through the ingress
#   HELM_TIMEOUT                 default 10m
# With infra/ci/cosign/cosign.pub in the repository, both images must carry a valid signature and
# SBOM attestation (infra/ci/sign-images.sh), and are deployed by the digest that was verified.
# Uses the current kubectl context: CI points KUBECONFIG at the environment's cluster.
set -euo pipefail

ENV=${1:?usage: deploy.sh <dev|qua|prod>}
: "${IMAGE_TAG:?IMAGE_TAG is required}"
REGISTRY=${REGISTRY:-docker.io/ebank}
NS=ebank-$ENV
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
VAULT_NS=${VAULT_SERVICE:-vault/vault}; VAULT_NS=${VAULT_NS%%/*}
VAULT_SVC=${VAULT_SERVICE:-vault/vault}; VAULT_SVC=${VAULT_SVC##*/}
PORT=${VAULT_LOCAL_PORT:-18200}

echo "== Vault: config of $ENV"
kubectl -n "$VAULT_NS" port-forward "svc/$VAULT_SVC" "$PORT:8200" >/dev/null 2>&1 &
FORWARD=$!
trap 'kill $FORWARD 2>/dev/null || true' EXIT
export VAULT_ADDR=http://127.0.0.1:$PORT
for _ in $(seq 30); do vault status >/dev/null 2>&1 && break; sleep 1; done
vault status >/dev/null 2>&1 || { echo "Vault is not reachable or is sealed" >&2; exit 1; }
if [[ -z "${VAULT_TOKEN:-}" ]]; then
  : "${VAULT_ROLE_ID:?VAULT_ROLE_ID and VAULT_SECRET_ID (AppRole ebank-$ENV-ci) are required}"
  VAULT_TOKEN=$(vault write -field=token auth/approle/login role_id="$VAULT_ROLE_ID" secret_id="$VAULT_SECRET_ID")
fi
export VAULT_TOKEN
"$ROOT/infra/vault/apply.sh" "$ENV" --config-only
vault token revoke -self >/dev/null 2>&1 || true
unset VAULT_TOKEN

# The realm goes with every release: the keycloak-config Job applies it after the upgrade.
extra=(--set-file "keycloak.realm=$ROOT/infra/keycloak/realms/ebank-realm.json")

PUBLIC_KEY=$ROOT/infra/ci/cosign/cosign.pub
if [[ -f "$PUBLIC_KEY" ]]; then
  echo "== Signatures"
  for image in ebank-backend ebank-front-web; do
    # The digest that carries a valid signature: Helm deploys it, so a tag moved afterwards changes nothing.
    digest=$(cosign verify --key "$PUBLIC_KEY" --insecure-ignore-tlog=true "$REGISTRY/$image:$IMAGE_TAG" |
      jq -r '.[0].critical.image."docker-manifest-digest"')
    cosign verify-attestation --key "$PUBLIC_KEY" --insecure-ignore-tlog=true --type cyclonedx \
      "$REGISTRY/$image@$digest" >/dev/null
    echo "  $image@$digest: signature and SBOM attestation verified"
    extra+=(--set "image.digests.$image=$digest")
  done
else
  echo "== Signatures: not checked (no infra/ci/cosign/cosign.pub yet)"
fi

echo "== Helm: $IMAGE_TAG to $NS"
previous=$(helm -n "$NS" history ebank --max 1 -o json 2>/dev/null | jq -r '.[0].revision // empty' || true)
helm upgrade --install ebank "$ROOT/infra/helm/ebank" \
  --namespace "$NS" --create-namespace \
  -f "$ROOT/infra/helm/ebank/values-$ENV.yaml" "${extra[@]}" \
  --set image.registry="$REGISTRY" --set image.tag="$IMAGE_TAG" \
  --atomic --timeout "${HELM_TIMEOUT:-10m}"

echo "== Smoke test"
if ! "$ROOT/infra/ci/smoke-test.sh" "$ENV"; then
  if [[ -n "$previous" ]]; then
    echo "Smoke test failed: rolling back to revision $previous" >&2
    helm -n "$NS" rollback ebank "$previous" --wait --timeout "${HELM_TIMEOUT:-10m}"
  else
    echo "Smoke test failed on the first install: nothing to roll back to" >&2
  fi
  exit 1
fi
helm -n "$NS" history ebank --max 3
