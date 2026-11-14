#!/usr/bin/env bash
# Checks a deployment from inside the cluster, right after `helm upgrade`: the pods being Ready
# says they started, this says they work together.
#
#   infra/ci/smoke-test.sh <dev|qua|prod>        (uses the current kubectl context)
#   SMOKE_URL=https://app.ebank.example infra/ci/smoke-test.sh prod   # also checks through the ingress
#
# Exits non-zero on the first failed check, so CI can roll back.
set -euo pipefail

ENV=${1:?usage: smoke-test.sh <dev|qua|prod>}
NS=${NAMESPACE:-ebank-$ENV}
# Actuator is on the HTTP port in dev, on the management port elsewhere.
ACTUATOR_PORT=8081
[[ "$ENV" == dev ]] && ACTUATOR_PORT=8080

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; exit 1; }
# Runs busybox wget in the backend pod (the images have no curl). Prints "<status> <body>".
in_backend() {
  kubectl -n "$NS" exec deploy/backend -- sh -c \
    "wget -q -S -O /tmp/smoke.body '$1' 2>/tmp/smoke.head; s=\$(grep -o 'HTTP/[0-9.]* [0-9]*' /tmp/smoke.head | tail -1 | cut -d' ' -f2); echo \"\$s \$(cat /tmp/smoke.body 2>/dev/null)\""
}

echo "== Smoke test of $NS"

health=$(kubectl -n "$NS" exec deploy/backend -- wget -qO- "http://127.0.0.1:$ACTUATOR_PORT/actuator/health" 2>/dev/null) ||
  fail "backend health endpoint does not answer"
[[ $(jq -r .status <<<"$health") == UP ]] || fail "backend health is $(jq -c . <<<"$health")"
pass "backend health UP"
for component in db redis vault; do
  status=$(jq -r ".components.$component.status // \"absent\"" <<<"$health")
  case "$status" in
    UP) pass "backend → $component UP" ;;
    absent) [[ "$component" == vault ]] && pass "backend → vault not used (configSource chart)" || fail "no $component health" ;;
    *) fail "backend → $component is $status" ;;
  esac
done

# busybox wget drops the body of an error response: the status is what is checked.
read -r status _ < <(in_backend "http://127.0.0.1:8080/api/v1/accounts")
[[ "$status" == 401 ]] || fail "API without a token answered $status, expected 401"
pass "API refuses a request without a token (401)"

read -r status body < <(in_backend "http://keycloak:8080/realms/ebank")
[[ "$status" == 200 && $(jq -r .realm <<<"$body") == ebank ]] || fail "Keycloak realm ebank answered $status"
pass "Keycloak serves the ebank realm"

read -r status _ < <(in_backend "http://keycloak:8080/realms/ebank/protocol/openid-connect/certs")
[[ "$status" == 200 ]] || fail "Keycloak signing keys answered $status"
pass "Keycloak publishes its signing keys"

config=$(kubectl -n "$NS" exec deploy/front-web -- wget -qO- http://127.0.0.1:8080/config.json 2>/dev/null) ||
  fail "front end does not serve /config.json"
for field in apiUrl wsUrl keycloak.url keycloak.realm keycloak.clientId; do
  [[ -n $(jq -r ".$field // empty" <<<"$config") ]] || fail "front end config.json has no $field"
done
pass "front end serves a complete /config.json ($(jq -r .apiUrl <<<"$config"))"

if [[ -n "${SMOKE_URL:-}" ]]; then
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$SMOKE_URL/healthz") || code=000
  [[ "$code" == 200 ]] || fail "$SMOKE_URL/healthz answered $code through the ingress"
  pass "front end reachable through the ingress ($SMOKE_URL)"
fi

echo "All smoke checks passed."
