#!/usr/bin/env bash
# Security scans, each in a pinned container, so CI and laptops run the same thing.
#
#   infra/ci/security/scan.sh secrets          gitleaks over the git history
#   infra/ci/security/scan.sh sast             Semgrep: community Java/TypeScript rules + ebank rules
#   infra/ci/security/scan.sh deps             Trivy on dependencies (the SBOMs when the build made them)
#   infra/ci/security/scan.sh iac              helm lint, then Trivy on Dockerfiles, compose and the rendered Helm chart
#   infra/ci/security/scan.sh image <image>    Trivy on a built image
#   infra/ci/security/scan.sh dast <url>       OWASP ZAP baseline against a running deployment
#   infra/ci/security/scan.sh all              everything except image and dast
#
# Fails on HIGH and CRITICAL findings (and on any leaked secret). Reports go to ./reports.
# Accepted findings are listed, with a reason, in .trivyignore.yaml and the rule files here.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE="$ROOT/infra/ci/security"
REPORTS=${REPORTS:-$ROOT/reports}
mkdir -p "$REPORTS"

GITLEAKS=zricethezav/gitleaks:v8.28.0
SEMGREP=semgrep/semgrep:1.141.0
TRIVY=aquasec/trivy:0.67.2
ZAP=ghcr.io/zaproxy/zaproxy:2.16.1
HELM=alpine/helm:3.19.0

# The same path inside the container as outside, so reports and paths match in Jenkins agents too.
# Tools run as the calling user: no root-owned files left in the workspace.
USER_FLAGS=(-u "$(id -u):$(id -g)" -e HOME=/tmp)
TRIVY_CACHE=${TRIVY_CACHE:-$HOME/.cache/trivy}
mkdir -p "$TRIVY_CACHE"
run() { docker run --rm "${USER_FLAGS[@]}" -v "$ROOT:$ROOT" -v "$REPORTS:$REPORTS" -w "$ROOT" "$@"; }
trivy() {
  docker run --rm "${USER_FLAGS[@]}" -v "$ROOT:$ROOT" -v "$REPORTS:$REPORTS" -w "$ROOT" \
    -v "$TRIVY_CACHE:/cache" -v /var/run/docker.sock:/var/run/docker.sock \
    --group-add "$(stat -c %g /var/run/docker.sock)" "$TRIVY" --cache-dir /cache "$@"
}
section() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

secrets() {
  section "Secrets (gitleaks)"
  run "$GITLEAKS" git "$ROOT" --config "$HERE/gitleaks.toml" --redact \
    --report-format sarif --report-path "$REPORTS/gitleaks.sarif" --exit-code 1
}

sast() {
  section "SAST (Semgrep)"
  run -e SEMGREP_SEND_METRICS=off "$SEMGREP" semgrep scan --metrics=off --error \
    --config p/java --config p/typescript --config p/secrets --config "$HERE/semgrep.yml" \
    --sarif --output "$REPORTS/semgrep.sarif" --text-output "$REPORTS/semgrep.txt" \
    backend/ front-web/src/
  tail -5 "$REPORTS/semgrep.txt" || true
}

deps() {
  section "Dependencies (Trivy)"
  # SARIF reports never fail the step; the table runs right after do.
  local report=(--severity HIGH,CRITICAL --ignore-unfixed --ignorefile "$HERE/.trivyignore.yaml")
  local common=("${report[@]}" --exit-code 1)
  local sbom="$ROOT/backend/target/classes/META-INF/sbom/application.cdx.json"
  if [[ -f "$sbom" ]]; then
    trivy sbom "${report[@]}" --format sarif --output "$REPORTS/trivy-backend.sarif" "$sbom"
    trivy sbom "${common[@]}" "$sbom"
  else
    echo "No SBOM (mvn -Pci package makes one): scanning the Maven project"
    trivy fs "${common[@]}" --scanners vuln "$ROOT/backend"
  fi
  trivy fs "${report[@]}" --scanners vuln --format sarif --output "$REPORTS/trivy-front-web.sarif" "$ROOT/front-web/package-lock.json"
  trivy fs "${common[@]}" --scanners vuln "$ROOT/front-web/package-lock.json"
}

iac() {
  section "Infrastructure as code (Trivy)"
  local rendered="$REPORTS/helm-rendered"
  local chart=infra/helm/ebank
  rm -rf "$rendered" && mkdir -p "$rendered"
  # Scan what is actually applied: the chart rendered with each environment's values, one file per
  # resource so an accepted finding can be scoped to one workload in .trivyignore.yaml. The
  # templates alone would not parse as Kubernetes YAML.
  for env in dev qua prod; do
    local values=(-f "$chart/values-$env.yaml")
    [[ "$env" == dev ]] && values+=(--set-file keycloak.realm=infra/keycloak/realms/ebank-realm.json)
    docker run --rm "${USER_FLAGS[@]}" -v "$ROOT:$ROOT" -w "$ROOT" "$HELM" \
      lint --strict "$chart" "${values[@]}"
    docker run --rm "${USER_FLAGS[@]}" -v "$ROOT:$ROOT" -w "$ROOT" "$HELM" \
      template ebank "$chart" --namespace "ebank-$env" "${values[@]}" \
      | awk -v dir="$rendered" -v env="$env" '
          function flush() { if (doc != "") { file = dir "/" env "-" tolower(kind) "-" name ".yaml"; printf "%s", doc > file; close(file) } doc = ""; kind = ""; name = "" }
          /^---$/ { flush(); next }
          /^kind: / && kind == "" { kind = $2 }
          /^  name: / && name == "" && inmeta { name = $2 }
          /^metadata:/ { inmeta = 1 } /^[a-z]/ && !/^metadata:/ { inmeta = 0 }
          { doc = doc $0 "\n" }
          END { flush() }'
  done
  local report=(--severity HIGH,CRITICAL --ignorefile "$HERE/.trivyignore.yaml")
  local common=("${report[@]}" --exit-code 1)
  # The chart is scanned rendered (above), so its folder is skipped in the plain file scan.
  trivy config "${report[@]}" --skip-dirs infra/helm --format sarif --output "$REPORTS/trivy-iac.sarif" "$ROOT/infra"
  trivy config "${report[@]}" --format sarif --output "$REPORTS/trivy-helm.sarif" "$rendered"
  trivy config "${common[@]}" --skip-dirs infra/helm "$ROOT/infra"
  trivy config "${common[@]}" "$rendered"
}

image() {
  section "Image ${1:?image name} (Trivy)"
  local name
  name=$(echo "$1" | tr '/:' '__')
  trivy image --severity HIGH,CRITICAL --ignore-unfixed --ignorefile "$HERE/.trivyignore.yaml" \
    --format sarif --output "$REPORTS/trivy-image-$name.sarif" "$1"
  trivy image --severity HIGH,CRITICAL --ignore-unfixed --ignorefile "$HERE/.trivyignore.yaml" --exit-code 1 "$1"
}

dast() {
  section "DAST (OWASP ZAP baseline) on ${1:?url}"
  # Passive scan only: safe against a shared environment. -I: warnings do not fail the build, FAIL rules do.
  docker run --rm --network host -v "$REPORTS:/zap/wrk:rw" -v "$HERE/zap:/zap/config:ro" -u "$(id -u)" "$ZAP" \
    zap-baseline.py -t "$1" -c /zap/config/rules.tsv -r zap-report.html -J zap-report.json -I
}

case "${1:-all}" in
  secrets) secrets ;;
  sast) sast ;;
  deps) deps ;;
  iac) iac ;;
  image) image "${2:-}" ;;
  dast) dast "${2:-}" ;;
  all) secrets; sast; deps; iac ;;
  *) echo "usage: $0 secrets|sast|deps|iac|image <image>|dast <url>|all" >&2; exit 2 ;;
esac
