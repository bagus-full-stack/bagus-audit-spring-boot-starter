#!/usr/bin/env bash
# Two app instances on one database: writes, reads, cursors, tenants, deletes and the purge job must agree.
#   docker compose -f docker-compose.yml -f docker-compose.multi.yml up -d --build && ./docker/multi-instance-test.sh
set -u

A="${A:-http://localhost:8080}"
B="${B:-http://localhost:8090}"
TOKEN_URL="${TOKEN_URL:-http://localhost:8081/default/token}"
PSQL=(docker compose -f docker-compose.yml -f docker-compose.multi.yml exec -T postgres psql -U audit -d audit -tA)

pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL  $1  ($2)"; fail=$((fail + 1)); }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected $2, got $3"; fi; }

token() {
  curl -s -X POST "$TOKEN_URL" -d "grant_type=client_credentials&client_id=$1&client_secret=secret" \
    | tr -d ' \n\r' | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}
ids() { grep -o '"id":"[^"]*"' | cut -d'"' -f4; }

echo "== waiting for both instances"
for url in "$A" "$B"; do
  for _ in $(seq 1 60); do curl -s "$url/actuator/health" | grep -q '"UP"' && break; sleep 2; done
done
W=$(token writer-acme); R=$(token reader-acme); ADM=$(token admin-acme); G=$(token reader-globex)

echo "== concurrent writes through both instances"
codes=$(mktemp -d)   # one file per request: parallel appends to a single file lose lines
for i in $(seq 1 20); do
  for n in A B; do
    url=$A; [ $n = B ] && url=$B
    curl -s -o /dev/null -w '%{http_code}' -X POST "$url/api/audit" -H "Authorization: Bearer $W" \
      -H 'Content-Type: application/json' -d '{"action":"MI"}' > "$codes/$n$i" &
  done
done
wait
check "40 writes answered 201" 40 "$(cat "$codes"/* | grep -o 201 | wc -l | tr -d ' ')"

echo "== reads agree"
fromA=$(curl -s "$A/api/audit?action=MI&size=200" -H "Authorization: Bearer $R" | ids | sort)
fromB=$(curl -s "$B/api/audit?action=MI&size=200" -H "Authorization: Bearer $R" | ids | sort)
check "instance A sees 40 entries" 40 "$(echo "$fromA" | grep -c .)"
check "instance B sees the same entries" "$fromA" "$fromB"

echo "== a cursor is valid on the other instance"
walked=$(mktemp); cursor=""; url=$A
for _ in $(seq 1 10); do
  page=$(curl -s "$url/api/audit?action=MI&size=10${cursor:+&cursor=$cursor}" -H "Authorization: Bearer $R")
  echo "$page" | ids >> "$walked"
  cursor=$(echo "$page" | sed -n 's/.*"nextCursor":"\([^"]*\)".*/\1/p')
  [ -z "$cursor" ] && break
  if [ "$url" = "$A" ]; then url=$B; else url=$A; fi
done
check "walk alternating A and B: 40 distinct entries" 40 "$(sort -u "$walked" | grep -c .)"
check "walk alternating A and B: no repeat" 40 "$(grep -c . "$walked")"

echo "== tenants"
check "another tenant sees nothing through A" 0 "$(curl -s "$A/api/audit?action=MI" -H "Authorization: Bearer $G" | ids | grep -c .)"
check "another tenant sees nothing through B" 0 "$(curl -s "$B/api/audit?action=MI" -H "Authorization: Bearer $G" | ids | grep -c .)"

echo "== delete through A, read through B"
one=$(echo "$fromA" | head -1)
check "delete through A" 204 "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$A/api/audit/$one" -H "Authorization: Bearer $ADM")"
check "B answers 404 for it" 404 "$(curl -s -o /dev/null -w '%{http_code}' "$B/api/audit/$one" -H "Authorization: Bearer $R")"

echo "== both instances run the purge job at once"
"${PSQL[@]}" -c "INSERT INTO audit_log (id, service_name, action, deleted_at)
  SELECT gen_random_uuid()::text, 'svc', 'OLD', now() - interval '3 days' FROM generate_series(1, 5000)" >/dev/null
for _ in $(seq 1 30); do
  left=$("${PSQL[@]}" -c "SELECT count(*) FROM audit_log WHERE action='OLD'")
  [ "$left" = "0" ] && break; sleep 2
done
check "5000 old rows purged" 0 "$left"
check "live rows untouched" 39 "$("${PSQL[@]}" -c "SELECT count(*) FROM audit_log WHERE action='MI' AND deleted_at IS NULL")"
db=$(docker compose -f docker-compose.yml -f docker-compose.multi.yml logs postgres 2>&1 | grep -c -i "deadlock detected")
check "no deadlock in PostgreSQL" 0 "$db"
errs=$(docker compose -f docker-compose.yml -f docker-compose.multi.yml logs audit audit2 2>&1 | grep -c " ERROR ")
check "no ERROR in either app log" 0 "$errs"

echo; echo "passed: $pass   failed: $fail"
[ "$fail" = 0 ]
