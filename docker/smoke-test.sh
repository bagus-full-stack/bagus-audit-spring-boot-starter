#!/usr/bin/env bash
# End-to-end checks against the docker compose stack (PostgreSQL + mock OAuth2 + sample app).
#   docker compose up -d --build && ./docker/smoke-test.sh
set -u

BASE="${BASE:-http://localhost:8080}"
TOKEN_URL="${TOKEN_URL:-http://localhost:8081/default/token}"
API="$BASE/api/audit"

pass=0; fail=0
ok()   { echo "  PASS  $1"; pass=$((pass + 1)); }
bad()  { echo "  FAIL  $1  ($2)"; fail=$((fail + 1)); }
check() { # check <label> <expected> <actual>
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected $2, got $3"; fi
}
contains() { # contains <label> <needle> <haystack>
  case "$3" in *"$2"*) ok "$1" ;; *) bad "$1" "missing $2" ;; esac
}
lacks() {
  case "$3" in *"$2"*) bad "$1" "unexpected $2" ;; *) ok "$1" ;; esac
}

token() { # token <client_id>
  curl -s -X POST "$TOKEN_URL" \
    -d "grant_type=client_credentials&client_id=$1&client_secret=secret" \
    | tr -d ' \n\r' | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}
status() { # status <method> <url> <token> [json body]
  local method=$1 url=$2 tok=$3 body=${4:-}
  local args=(-s -o /dev/null -w '%{http_code}' -X "$method" "$url")
  [ -n "$tok" ]  && args+=(-H "Authorization: Bearer $tok")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}"
}
call() { # call <method> <url> <token> [json body]  -> response body
  local method=$1 url=$2 tok=$3 body=${4:-}
  local args=(-s -X "$method" "$url")
  [ -n "$tok" ]  && args+=(-H "Authorization: Bearer $tok")
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  curl "${args[@]}"
}
id_of() { sed -n 's/.*"id":"\([^"]*\)".*/\1/p'; }

echo "== waiting for the app"
for _ in $(seq 1 60); do
  [ "$(status GET "$BASE/actuator/health" "")" = "200" ] && break
  sleep 2
done
check "health is UP" 200 "$(status GET "$BASE/actuator/health" "")"

echo "== tokens"
ADMIN=$(token admin-acme); WRITER=$(token writer-acme)
READER=$(token reader-acme); OTHER=$(token reader-globex)
for t in ADMIN WRITER READER OTHER; do
  [ -n "${!t}" ] && ok "token $t issued" || bad "token $t issued" "empty (is mock-oauth2 up?)"
done

echo "== security"
check "no token -> 401"          401 "$(status GET "$API" "")"
check "garbage token -> 401"     401 "$(status GET "$API" "not-a-jwt")"
check "valid signature, other audience -> 401" 401 "$(status GET "$API" "$(token other-app)")"
check "reader cannot POST -> 403" 403 "$(status POST "$API" "$READER" '{"action":"X"}')"
check "writer cannot GET -> 403"  403 "$(status GET "$API" "$WRITER")"
check "writer cannot DELETE -> 403" 403 "$(status DELETE "$API/some-id" "$WRITER")"

echo "== create and read"
created=$(call POST "$API" "$WRITER" '{"action":"SMOKE_CREATE","payload":"hello","actor":"mallory"}')
ID=$(echo "$created" | id_of)
[ -n "$ID" ] && ok "create returns an id" || bad "create returns an id" "$created"
contains "actor is the token subject, not the body" '"createdBy":"writer-acme"' "$created"
contains "tenant comes from the token"             '"tenantId":"acme"'          "$created"
check "reader reads it by id -> 200" 200 "$(status GET "$API/$ID" "$READER")"
contains "list shows it" "$ID" "$(call GET "$API?size=50" "$READER")"

echo "== tenant isolation"
lacks "other tenant list does not show it" "$ID" "$(call GET "$API?size=50&tenantId=acme" "$OTHER")"
check "other tenant read by id -> 404" 404 "$(status GET "$API/$ID" "$OTHER")"

echo "== batch"
check "batch of 2 -> 201" 201 "$(status POST "$API/batch" "$WRITER" '{"entries":[{"action":"B1"},{"action":"B2"}]}')"
check "empty batch -> 400" 400 "$(status POST "$API/batch" "$WRITER" '{"entries":[]}')"

echo "== pagination"
for i in 1 2 3 4 5; do call POST "$API" "$WRITER" "{\"action\":\"PAGE_$i\"}" > /dev/null; done
seen=0; cursor=""
for _ in $(seq 1 40); do
  url="$API?size=2"; [ -z "$cursor" ] || url="$API?size=2&cursor=$cursor"
  page=$(call GET "$url" "$READER")
  seen=$((seen + $(echo "$page" | grep -o '"action"' | wc -l)))
  cursor=$(echo "$page" | sed -n 's/.*"nextCursor":"\([^"]*\)".*/\1/p')
  [ -z "$cursor" ] && break
done
[ "$seen" -ge 5 ] && ok "cursor walk reached every entry ($seen seen)" || bad "cursor walk" "only $seen seen"
check "malformed cursor -> 400" 400 "$(status GET "$API?cursor=abc" "$READER")"

echo "== delete and GDPR"
check "writer cannot erase -> 403" 403 "$(status DELETE "$API/$ID/personal-data" "$WRITER")"
check "admin erases personal data -> 204" 204 "$(status DELETE "$API/$ID/personal-data" "$ADMIN")"
erased=$(call GET "$API/$ID" "$READER")
contains "entry is marked erased" '"gdprErased":true' "$erased"
lacks    "payload is gone"        'hello'             "$erased"
check "admin deletes -> 204"      204 "$(status DELETE "$API/$ID" "$ADMIN")"
check "deleted entry -> 404"      404 "$(status GET "$API/$ID" "$READER")"

echo
echo "passed: $pass   failed: $fail"
[ "$fail" -eq 0 ]
