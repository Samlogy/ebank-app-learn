#!/usr/bin/env bash
# Signs the images CI just pushed and attaches their CycloneDX SBOM as a signed attestation, with
# the key pair of infra/ci/cosign (see README "Image signing"). Images are signed by digest, so the
# signature covers exactly what was pushed.
#
#   COSIGN_KEY=<private key file> COSIGN_PASSWORD=... IMAGE_TAG=<git sha> infra/ci/sign-images.sh
#
# No public transparency log: cosign/signing-config.json lists no Rekor, so nothing about the
# images leaves the registry. deploy.sh verifies with the public key (cosign/cosign.pub).
set -euo pipefail

: "${IMAGE_TAG:?IMAGE_TAG is required}"
: "${COSIGN_KEY:?COSIGN_KEY (the private key file) is required}"
: "${COSIGN_PASSWORD:?COSIGN_PASSWORD is required}"
export COSIGN_PASSWORD
REGISTRY=${REGISTRY:-docker.io/ebank}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CONFIG=$ROOT/infra/ci/cosign/signing-config.json

declare -A SBOM=(
  [ebank-backend]=$ROOT/backend/target/classes/META-INF/sbom/application.cdx.json
  [ebank-front-web]=$ROOT/front-web/bom.json
)

# deploy.sh requires both the signature and the attestation: check the SBOMs before signing anything.
for image in "${!SBOM[@]}"; do
  [[ -f "${SBOM[$image]}" ]] || { echo "no SBOM for $image at ${SBOM[$image]}: build it first" >&2; exit 1; }
done

for image in ebank-backend ebank-front-web; do
  # After the push, the local image knows its digest in the registry (docker drops "docker.io/").
  digest=$(docker inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$REGISTRY/$image:$IMAGE_TAG" |
    grep -E "/$image@sha256:" | head -1 | cut -d@ -f2)
  [[ -n "$digest" ]] || { echo "no registry digest for $REGISTRY/$image:$IMAGE_TAG: push it first" >&2; exit 1; }
  ref=$REGISTRY/$image@$digest
  echo "== $ref"
  cosign sign --yes --key "$COSIGN_KEY" --signing-config "$CONFIG" "$ref"
  cosign attest --yes --key "$COSIGN_KEY" --signing-config "$CONFIG" --type cyclonedx --predicate "${SBOM[$image]}" "$ref"
done
