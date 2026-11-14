#!/usr/bin/env bash
# End-to-end test of the notifications module against the local dev stack.
#
# Needs: the compose infrastructure (docker compose up -d --wait), the backend on :8080
# (dev profile), and curl, jq, node 22+.
#
#   infra/scripts/test-notifications.sh
#
# What it does, in order:
#   0. Prepares Keycloak (dev only): the ebank-notifications service account, a dev-only
#      "ebank-cli" client with the password grant so curl can get tokens, and three new users.
#   1. Security: 401 without a token, WebSocket refused without a token or on a forbidden queue.
#   2. Offline user: a deposit creates a notification and an email in Mailpit.
#   3. Online user: a transfer is pushed live over STOMP; the offline receiver gets an email.
#   4. Internal transfer and cancelled transfer.
#   5. Read state and ownership.
#   6. Preferences: email off means no email.
#   7. WebSocket token refresh.
#   8. Outbox rows and metrics.
#
# Every run creates new users, so it can be run again without cleanup.

set -uo pipefail

API=${API:-http://localhost:8080}
KC=${KC:-http://localhost:8180}
MAILPIT=${MAILPIT:-http://localhost:8025}
KC_ADMIN=${KEYCLOAK_ADMIN_USERNAME:-admin}
KC_ADMIN_PASSWORD=${KEYCLOAK_ADMIN_PASSWORD:-admin}
NOTIFICATIONS_SECRET=${KEYCLOAK_NOTIFICATIONS_CLIENT_SECRET:-dev-notifications-secret}
PASSWORD='Test-1234!'
HERE=$(cd "$(dirname "$0")" && pwd)
STOMP="node $HERE/stomp-client.mjs"
WORK=$(mktemp -d)
trap 'kill $(jobs -p) 2>/dev/null; rm -rf "$WORK"' EXIT

PASSED=0
FAILED=0
green() { printf '\033[32m%s\033[0m\n' "$*"; }
red() { printf '\033[31m%s\033[0m\n' "$*"; }
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
pass() { PASSED=$((PASSED + 1)); green "  PASS  $*"; }
fail() { FAILED=$((FAILED + 1)); red "  FAIL  $*"; }
check() { # check "description" actual expected
  if [[ "$2" == "$3" ]]; then pass "$1"; else fail "$1 (expected '$3', got '$2')"; fi
}

for tool in curl jq node; do
  command -v $tool >/dev/null || { red "$tool is required"; exit 1; }
done
curl -sf "$API/actuator/health" >/dev/null || { red "Backend not reachable at $API"; exit 1; }
curl -sf "$KC/realms/ebank" >/dev/null || { red "Keycloak not reachable at $KC"; exit 1; }

# ---------------------------------------------------------------------------- helpers

# api METHOD PATH TOKEN [JSON] -> prints the body, sets $STATUS. Adds an Idempotency-Key to POSTs.
api() {
  local method=$1 path=$2 token=$3 body=${4:-}
  local args=(-s -o "$WORK/body" -w '%{http_code}' -X "$method" "$API$path" -H 'Content-Type: application/json')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ "$method" == POST ]] && args+=(-H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)")
  [[ -n "$body" ]] && args+=(-d "$body")
  STATUS=$(curl "${args[@]}")
  cat "$WORK/body"
}

kc() { # kc METHOD PATH [JSON]: Keycloak Admin API on the ebank realm
  curl -s -X "$1" "$KC/admin/realms/ebank$2" -H "Authorization: Bearer $KC_TOKEN" \
    -H 'Content-Type: application/json' ${3:+-d "$3"}
}

token_for() {
  curl -s "$KC/realms/ebank/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=ebank-cli -d "username=$1" -d "password=$PASSWORD" -d scope=openid |
    jq -r .access_token
}

create_user() { # create_user EMAIL [ADMIN] -> prints the Keycloak user id (= JWT sub)
  kc POST /users "$(jq -n --arg e "$1" --arg p "$PASSWORD" '{
      username: $e, email: $e, emailVerified: true, enabled: true,
      firstName: "Test", lastName: ($e | split("@")[0]), requiredActions: [],
      credentials: [{type: "password", value: $p, temporary: false}]}')" >/dev/null
  local id
  id=$(kc GET "/users?username=$(jq -rn --arg e "$1" '$e|@uri')&exact=true" | jq -r '.[0].id')
  if [[ "${2:-}" == ADMIN ]]; then
    kc POST "/users/$id/role-mappings/realm" "[$(kc GET /roles/ADMIN)]" >/dev/null
  fi
  echo "$id"
}

# wait_for_notification TOKEN JQ_FILTER [SECONDS]: polls the API until the filter matches an item.
wait_for_notification() {
  local token=$1 filter=$2 deadline=$((SECONDS + ${3:-15}))
  while ((SECONDS < deadline)); do
    local match
    match=$(api GET '/api/v1/notifications?size=100' "$token" | jq -c "[.items[] | select($filter)] | first // empty")
    [[ -n "$match" ]] && { echo "$match"; return 0; }
    sleep 0.5
  done
  return 1
}

# mail_count EMAIL [SUBJECT]: number of messages in Mailpit to that address (with that exact subject).
mail_count() {
  curl -s "$MAILPIT/api/v1/search?query=$(jq -rn --arg q "to:\"$1\"" '$q|@uri')" |
    jq --arg s "${2:-}" '[.messages[] | select($s == "" or .Subject == $s)] | length'
}

wait_for_mail() { # wait_for_mail EMAIL SUBJECT [SECONDS]
  local deadline=$((SECONDS + ${3:-15}))
  while ((SECONDS < deadline)); do
    (($(mail_count "$1" "$2") > 0)) && return 0
    sleep 0.5
  done
  return 1
}

# ---------------------------------------------------------------------------- 0. setup

step "0. Keycloak setup (dev only)"
KC_TOKEN=$(curl -s "$KC/realms/master/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli -d "username=$KC_ADMIN" -d "password=$KC_ADMIN_PASSWORD" | jq -r .access_token)
[[ "$KC_TOKEN" != null && -n "$KC_TOKEN" ]] || { red "Keycloak admin login failed"; exit 1; }

# The realm is only imported on Keycloak's first start, so an older local Keycloak may lack this client.
if [[ $(kc GET '/clients?clientId=ebank-notifications' | jq length) == 0 ]]; then
  kc POST /clients "$(jq -n --arg s "$NOTIFICATIONS_SECRET" '{
      clientId: "ebank-notifications", enabled: true, publicClient: false, secret: $s,
      standardFlowEnabled: false, directAccessGrantsEnabled: false, serviceAccountsEnabled: true}')" >/dev/null
  NOTIF_ID=$(kc GET '/clients?clientId=ebank-notifications' | jq -r '.[0].id')
  SA_ID=$(kc GET "/clients/$NOTIF_ID/service-account-user" | jq -r .id)
  RM_ID=$(kc GET '/clients?clientId=realm-management' | jq -r '.[0].id')
  kc POST "/users/$SA_ID/role-mappings/clients/$RM_ID" "[$(kc GET "/clients/$RM_ID/roles/view-users")]" >/dev/null
  echo "  created client ebank-notifications (service account with view-users)"
else
  echo "  client ebank-notifications already exists"
fi

# ebank-web deliberately has no password grant. This client exists only in your local Keycloak,
# never in the versioned realm, so curl can get tokens. Direct grants skip the browser flow's TOTP.
if [[ $(kc GET '/clients?clientId=ebank-cli' | jq length) == 0 ]]; then
  kc POST /clients '{
      "clientId": "ebank-cli", "name": "Local testing only", "enabled": true, "publicClient": true,
      "standardFlowEnabled": false, "directAccessGrantsEnabled": true,
      "protocolMappers": [{"name": "audience-ebank-api", "protocol": "openid-connect",
        "protocolMapper": "oidc-audience-mapper",
        "config": {"included.client.audience": "ebank-api", "access.token.claim": "true", "id.token.claim": "false"}}]}' >/dev/null
  echo "  created dev-only client ebank-cli (password grant)"
else
  echo "  client ebank-cli already exists"
fi

RUN=$(date +%s)
ALICE_EMAIL="alice.$RUN@ebank.local"
BOB_EMAIL="bob.$RUN@ebank.local"
ADMIN_EMAIL="admin.$RUN@ebank.local"
ALICE_ID=$(create_user "$ALICE_EMAIL")
BOB_ID=$(create_user "$BOB_EMAIL")
create_user "$ADMIN_EMAIL" ADMIN >/dev/null
ALICE=$(token_for "$ALICE_EMAIL")
BOB=$(token_for "$BOB_EMAIL")
ADMIN=$(token_for "$ADMIN_EMAIL")
for t in ALICE BOB ADMIN; do
  [[ "${!t}" != null && -n "${!t}" ]] || { red "Could not get a token for $t"; exit 1; }
done
echo "  users: $ALICE_EMAIL ($ALICE_ID), $BOB_EMAIL ($BOB_ID), $ADMIN_EMAIL (ADMIN)"

new_account() { # new_account TOKEN -> active EUR account id
  local id
  id=$(api POST /api/v1/accounts "$1" '{"type":"CURRENT","currency":"EUR"}' | jq -r .accountId)
  api PATCH "/api/v1/accounts/$id/activate" "$ADMIN" >/dev/null
  echo "$id"
}
ALICE_ACC=$(new_account "$ALICE")
ALICE_ACC2=$(new_account "$ALICE")
BOB_ACC=$(new_account "$BOB")
echo "  accounts: alice $ALICE_ACC + $ALICE_ACC2, bob $BOB_ACC"

transfer() { # transfer TOKEN FROM TO AMOUNT -> body, sets STATUS
  api POST /api/v1/transactions "$1" \
    "$(jq -n --arg f "$2" --arg t "$3" --arg a "$4" '{senderAccountId:$f, receiverAccountId:$t, amount:($a|tonumber), currency:"EUR", reference:"e2e test"}')"
}

# ---------------------------------------------------------------------------- 1. security

step "1. Security"
api GET /api/v1/notifications "" >/dev/null
check "REST without a token is 401" "$STATUS" 401

out=$($STOMP --timeout 5)
[[ "$out" == *ERROR* ]] && pass "STOMP CONNECT without a token is refused" || fail "STOMP CONNECT without a token: $out"

out=$($STOMP --token not-a-jwt --timeout 5)
[[ "$out" == *ERROR* ]] && pass "STOMP CONNECT with an invalid token is refused" || fail "STOMP with an invalid token: $out"

out=$($STOMP --token "$ALICE" --subscribe /queue/notifications --timeout 5)
[[ "$out" == *CONNECTED* && "$out" == *ERROR* ]] && pass "Direct /queue subscription is refused" || fail "Direct /queue subscription: $out"

out=$($STOMP --token "$ALICE" --subscribe /topic/everything --timeout 5)
[[ "$out" == *ERROR* ]] && pass "Any other subscription is refused" || fail "Other subscription: $out"

# ---------------------------------------------------------------------------- 2. offline user

step "2. Deposit to alice while she is offline"
api POST /api/v1/transactions/deposits "$ADMIN" \
  "$(jq -n --arg r "$ALICE_ACC" '{receiverAccountId:$r, amount:100, currency:"EUR", reference:"salary"}')" >/dev/null
check "Deposit accepted" "$STATUS" 201
n=$(wait_for_notification "$ALICE" '.type == "TRANSFER_RECEIVED"') && pass "alice has TRANSFER_RECEIVED: $(jq -c '{amount,currency,counterparty}' <<<"$n")" || fail "alice got no notification for the deposit"
wait_for_mail "$ALICE_EMAIL" "You received 100.00 EUR" && pass "Offline alice got an email (see $MAILPIT)" || fail "No email for alice"

# ---------------------------------------------------------------------------- 3. online user

step "3. alice online (STOMP), bob offline: alice sends 30.50 EUR to bob"
$STOMP --token "$ALICE" --count 1 --timeout 20 >"$WORK/alice-ws.log" 2>&1 &
WS_PID=$!
for _ in $(seq 1 50); do grep -q SUBSCRIBED "$WORK/alice-ws.log" && break; sleep 0.1; done
sleep 1 # the server registers the subscription asynchronously
MAILS_ALICE_BEFORE=$(mail_count "$ALICE_EMAIL")

TX=$(transfer "$ALICE" "$ALICE_ACC" "$BOB_ACC" 30.50 | jq -r .transactionId)
check "Transfer accepted" "$STATUS" 201

wait $WS_PID
pushed=$(grep '^MESSAGE ' "$WORK/alice-ws.log" | head -1 | cut -d' ' -f2-)
check "alice got TRANSFER_SENT pushed live" "$(jq -r .type <<<"${pushed:-{\}}")" TRANSFER_SENT
check "Pushed notification names the transaction" "$(jq -r .transactionId <<<"${pushed:-{\}}")" "$TX"

n=$(wait_for_notification "$BOB" ".transactionId == \"$TX\"") && check "bob has TRANSFER_RECEIVED in the API" "$(jq -r .type <<<"$n")" TRANSFER_RECEIVED || fail "bob has no notification"
wait_for_mail "$BOB_EMAIL" "You received 30.50 EUR" && pass "Offline bob got an email" || fail "No email for bob"
body=$(curl -s "$MAILPIT/api/v1/message/$(curl -s "$MAILPIT/api/v1/search?query=$(jq -rn --arg q "to:\"$BOB_EMAIL\"" '$q|@uri')" | jq -r '.messages[0].ID')" | jq -r .Text)
[[ "$body" != *"$BOB_ACC"* && "$body" != *"e2e test"* ]] && pass "Email masks the account ID and leaves out the reference" || fail "Email leaks details: $body"
sleep 2
check "Online alice got no email for TRANSFER_SENT" "$(mail_count "$ALICE_EMAIL")" "$MAILS_ALICE_BEFORE"
check "bob's list holds no TRANSFER_SENT" "$(api GET /api/v1/notifications "$BOB" | jq '[.items[] | select(.type=="TRANSFER_SENT")] | length')" 0

# ---------------------------------------------------------------------------- 4. internal and cancelled

step "4. Internal transfer and cancelled transfer"
TX=$(transfer "$ALICE" "$ALICE_ACC" "$ALICE_ACC2" 10 | jq -r .transactionId)
wait_for_notification "$ALICE" ".transactionId == \"$TX\"" >/dev/null
check "Own accounts: exactly one TRANSFER_INTERNAL" \
  "$(api GET '/api/v1/notifications?size=100' "$ALICE" | jq -c "[.items[] | select(.transactionId == \"$TX\") | .type]")" '["TRANSFER_INTERNAL"]'

BOB_COUNT_BEFORE=$(api GET /api/v1/notifications "$BOB" | jq .totalElements)
code=$(transfer "$ALICE" "$ALICE_ACC" "$BOB_ACC" 100000 | jq -r .code)
check "Transfer over the balance is refused" "$STATUS/$code" 422/INSUFFICIENT_FUNDS
wait_for_notification "$ALICE" '.type == "TRANSFER_CANCELLED"' >/dev/null && pass "alice has TRANSFER_CANCELLED" || fail "No TRANSFER_CANCELLED for alice"
sleep 1
check "bob is not told about the cancelled transfer" "$(api GET /api/v1/notifications "$BOB" | jq .totalElements)" "$BOB_COUNT_BEFORE"

# ---------------------------------------------------------------------------- 5. read state

step "5. Read state and ownership"
unread=$(api GET /api/v1/notifications/unread-count "$ALICE" | jq .count)
((unread >= 4)) && pass "alice has $unread unread" || fail "alice unread count is $unread"
ID=$(api GET /api/v1/notifications "$ALICE" | jq -r '.items[0].id')

api PATCH "/api/v1/notifications/$ID/read" "$BOB" >/dev/null
check "bob cannot mark alice's notification (404)" "$STATUS" 404
check "Unknown id is 404 too" "$(api PATCH /api/v1/notifications/00000000-0000-0000-0000-000000000000/read "$ALICE" >/dev/null; echo "$STATUS")" 404

check "alice marks it read" "$(api PATCH "/api/v1/notifications/$ID/read" "$ALICE" | jq .read)" true
check "Unread count went down by one" "$(api GET /api/v1/notifications/unread-count "$ALICE" | jq .count)" $((unread - 1))
check "unreadOnly filter excludes it" "$(api GET '/api/v1/notifications?unreadOnly=true&size=100' "$ALICE" | jq "[.items[] | select(.id == \"$ID\")] | length")" 0
check "read-all marks the rest" "$(api POST /api/v1/notifications/read-all "$ALICE" | jq .updated)" $((unread - 1))
check "Unread count is now 0" "$(api GET /api/v1/notifications/unread-count "$ALICE" | jq .count)" 0
api GET '/api/v1/notifications?size=500' "$ALICE" >/dev/null
check "Page size over 100 is rejected" "$STATUS" 400

# ---------------------------------------------------------------------------- 6. preferences

step "6. Preferences"
check "Defaults: email on, SMS off" "$(api GET /api/v1/notifications/preferences "$BOB" | jq -c '{emailEnabled,smsEnabled}')" '{"emailEnabled":true,"smsEnabled":false}'
api PUT /api/v1/notifications/preferences "$BOB" '{"emailEnabled":true}' >/dev/null
check "Missing field is rejected (400)" "$STATUS" 400
check "bob turns email off" "$(api PUT /api/v1/notifications/preferences "$BOB" '{"emailEnabled":false,"smsEnabled":true}' | jq -c '{emailEnabled,smsEnabled}')" '{"emailEnabled":false,"smsEnabled":true}'

MAILS_BOB_BEFORE=$(mail_count "$BOB_EMAIL")
TX=$(transfer "$ALICE" "$ALICE_ACC" "$BOB_ACC" 5 | jq -r .transactionId)
wait_for_notification "$BOB" ".transactionId == \"$TX\"" >/dev/null && pass "bob still gets the in-app notification" || fail "bob has no notification"
sleep 3
check "No email once email is off" "$(mail_count "$BOB_EMAIL")" "$MAILS_BOB_BEFORE"
echo "  SMS is a logging stub: the backend log shows 'SMS for notification ... not sent' only if the user has a phone attribute in Keycloak."
api PUT /api/v1/notifications/preferences "$BOB" '{"emailEnabled":true,"smsEnabled":false}' >/dev/null

# ---------------------------------------------------------------------------- 7. token refresh

step "7. WebSocket token refresh"
out=$($STOMP --token "$ALICE" --refresh "$(token_for "$ALICE_EMAIL")" --timeout 3)
[[ "$out" == *REFRESHED* && "$out" != *ERROR* ]] && pass "Refreshing with a token of the same user keeps the connection" || fail "Refresh: $out"
out=$($STOMP --token "$ALICE" --refresh "$BOB" --timeout 3)
[[ "$out" == *ERROR* ]] && pass "Refreshing with another user's token closes the connection" || fail "Refresh with another user: $out"

# ---------------------------------------------------------------------------- 8. outbox and metrics

step "8. Outbox and metrics"
if command -v docker >/dev/null; then
  PG=$(docker ps --format '{{.Names}}' | grep -E 'postgres-1$' | grep -v keycloak | head -1)
  if [[ -n "$PG" ]]; then
    docker exec "$PG" psql -U "${DB_USERNAME:-ebank}" -d ebank -c \
      "SELECT event_type, status, count(*) FROM outbox_event GROUP BY 1, 2 ORDER BY 1, 2;"
    check "No FAILED outbox events" "$(docker exec "$PG" psql -U "${DB_USERNAME:-ebank}" -d ebank -tAc "SELECT count(*) FROM outbox_event WHERE status = 'FAILED'")" 0
  fi
fi
curl -s "$API/actuator/prometheus" | grep -E '^ebank_(notifications|websocket)' | sed 's/^/  /'

# ---------------------------------------------------------------------------- summary

printf '\n'
if ((FAILED == 0)); then green "All $PASSED checks passed."; else red "$FAILED failed, $PASSED passed."; fi
echo "Emails: $MAILPIT    Try it yourself: export TOKEN=\$(curl -s $KC/realms/ebank/protocol/openid-connect/token -d grant_type=password -d client_id=ebank-cli -d username=$ALICE_EMAIL -d password='$PASSWORD' | jq -r .access_token)"
exit $((FAILED > 0))
