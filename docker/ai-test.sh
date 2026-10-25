#!/usr/bin/env bash
# The audit assistant against a real Ollama model, through the REST API.
#   docker compose -f docker-compose.yml -f docker-compose.ai.yml up -d --build && ./docker/ai-test.sh
set -u

BASE="${BASE:-http://localhost:8080}"
TOKEN_URL="${TOKEN_URL:-http://localhost:8081/default/token}"
API="$BASE/api/audit"

pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL  $1  ($2)"; fail=$((fail + 1)); }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected $2, got $3"; fi; }
contains() { case "$3" in *"$2"*) ok "$1" ;; *) bad "$1" "missing $2 in: ${3:0:200}" ;; esac; }
lacks()    { case "$3" in *"$2"*) bad "$1" "unexpected $2" ;; *) ok "$1" ;; esac; }

token() {
  curl -s -X POST "$TOKEN_URL" -d "grant_type=client_credentials&client_id=$1&client_secret=secret" \
    | tr -d ' \n\r' | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}
ask() { # ask <token> <question>  -> body
  curl -s -X POST "$API/ask" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d "{\"question\":\"$2\"}"
}
ask_status() {
  curl -s -o /dev/null -w '%{http_code}' -X POST "$API/ask" ${1:+-H "Authorization: Bearer $1"} \
    -H 'Content-Type: application/json' -d "{\"question\":\"$2\"}"
}
create() { # create <token> <json>  -> id
  curl -s -X POST "$API" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$2" \
    | sed -n 's/.*"id":"\([^"]*\)".*/\1/p'
}

echo "== waiting for the app"
for _ in $(seq 1 90); do curl -s "$BASE/actuator/health" | grep -q '"UP"' && break; sleep 2; done
W=$(token writer-acme); R=$(token reader-acme); ADM=$(token admin-acme); G=$(token reader-globex)

echo "== data"
REFUND=$(create "$W" '{"action":"REFUND_ISSUED","payload":"refund of 120 EUR for order 42 approved by the support team"}')
create "$W" '{"action":"LOGIN","payload":"user alice signed in from Paris"}' >/dev/null
create "$W" '{"action":"EXPORT","payload":"monthly sales report exported as CSV"}' >/dev/null

echo "== access"
check "no token is 401" 401 "$(ask_status "" "refund")"
check "a writer without the read role is 403" 403 "$(ask_status "$W" "refund")"
check "a blank question is 400" 400 "$(ask_status "$R" " ")"

echo "== answers (the index fills in the background, so retry)"
body=""
for _ in $(seq 1 60); do
  body=$(ask "$R" "Which refund was approved for order 42?")
  case "$body" in *"$REFUND"*) break ;; esac
  sleep 3
done
contains "the refund entry is a source" "$REFUND" "$body"
contains "the answer is not empty" '"answer":"' "$body"
lacks "an empty answer is not returned" '"answer":""' "$body"

echo "== tenants"
other=$(ask "$G" "Which refund was approved for order 42?")
lacks "another tenant never sees the refund" "$REFUND" "$other"

echo "== delete"
curl -s -o /dev/null -X DELETE "$API/$REFUND" -H "Authorization: Bearer $ADM"
after=$(ask "$R" "Which refund was approved for order 42?")
lacks "a deleted entry is no longer a source" "$REFUND" "$after"

echo; echo "passed: $pass   failed: $fail"
[ "$fail" = 0 ]
