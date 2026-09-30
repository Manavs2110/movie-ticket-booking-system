#!/usr/bin/env bash
# Verifies the booking system end to end: tests → fresh stack → Postman lifecycle (newman) → data checks → teardown.
#   ./.claude/skills/verify-booking-system/verify.sh [--skip-tests]
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"
COLLECTION="scripts/demo/postman/MovieBooking-Lifecycle-16.postman_collection.json"
SKIP_TESTS=false; [ "${1:-}" = "--skip-tests" ] && SKIP_TESTS=true

G=$'\033[32m'; R=$'\033[31m'; B=$'\033[1m'; N=$'\033[0m'
pass() { echo "${G}PASS${N}  $*"; }
fail() { echo "${R}FAIL${N}  $*"; docker compose down -v >/dev/null 2>&1; exit 1; }
step() { echo; echo "${B}== $*${N}"; }
sql()  { docker exec mtbs-postgres psql -U moviebooking -d moviebooking -tAc "$1"; }

step "1. Preconditions"
docker info >/dev/null 2>&1 || fail "Docker is not running"
JH=$(/usr/libexec/java_home -v 21 2>/dev/null)
[ -z "$JH" ] && [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ] && JH=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
[ -n "$JH" ] || fail "JDK 21 not found"
export JAVA_HOME="$JH"
command -v npx >/dev/null || fail "npx (Node.js) not found; needed for newman"
pass "Docker, JDK 21 ($JAVA_HOME), npx"

step "2. Test suite (mvn clean test)"
if $SKIP_TESTS; then
  echo "skipped (--skip-tests)"
else
  mvn -q clean test > /tmp/verify-mvn.log 2>&1; MVN=$?
  RESULT=$(python3 - <<'EOF'
import glob, re
t = e = f = 0
for x in glob.glob('target/surefire-reports/TEST-*.xml'):
    m = re.search(r'tests="(\d+)" errors="(\d+)" skipped="\d+" failures="(\d+)"', open(x).read(800))
    if m: t += int(m[1]); e += int(m[2]); f += int(m[3])
print(t, e, f)
EOF
)
  read -r TESTS ERRORS FAILURES <<<"$RESULT"
  [ "$MVN" -eq 0 ] && [ "$ERRORS" = 0 ] && [ "$FAILURES" = 0 ] && [ "$TESTS" -gt 0 ] \
    || fail "tests: $TESTS run, $FAILURES failures, $ERRORS errors (log: /tmp/verify-mvn.log)"
  pass "$TESTS tests, 0 failures, 0 errors"
fi

step "3. Fresh stack"
docker compose down -v >/dev/null 2>&1
docker compose up -d --build >/tmp/verify-compose.log 2>&1 || fail "docker compose up (log: /tmp/verify-compose.log)"
for i in $(seq 1 90); do curl -sf localhost:8080/api/cities >/dev/null && break; sleep 2; done
curl -sf localhost:8080/api/cities >/dev/null || fail "API did not come up (docker compose logs app)"
pass "stack up, API answering"

step "4. End-to-end: Postman lifecycle collection (newman)"
npx -y newman@6 run "$COLLECTION" --reporters cli > /tmp/verify-newman.log 2>&1; NEWMAN=$?
grep -E "│ +(requests|assertions) " /tmp/verify-newman.log
[ "$NEWMAN" -eq 0 ] || fail "newman reported failures (log: /tmp/verify-newman.log)"
pass "16 requests, all assertions passed"

step "5. Data checks"
sleep 3   # let the outbox publisher (every 2 s) and the consumer finish
[ "$(sql "SELECT status || ':' || refund_status FROM booking WHERE id = 1")" = "CANCELLED:SUCCESS" ] || fail "booking 1 is not CANCELLED with a successful refund"
[ "$(sql "SELECT count(*) FROM payment WHERE booking_id = 1")" = "1" ] || fail "booking 1 does not have exactly one payment"
[ "$(sql "SELECT count(*) FROM seat_lock WHERE show_id = 337")" = "0" ] || fail "seat locks left behind for show 337"
[ "$(sql "SELECT count(*) FROM outbox_event WHERE aggregate_id = 1 AND event_type <> 'SHOW_REMINDER' AND status <> 'PROCESSED'")" = "0" ] || fail "events for booking 1 not all PROCESSED"
[ "$(sql "SELECT status FROM outbox_event WHERE aggregate_id = 1 AND event_type = 'SHOW_REMINDER'")" = "CANCELLED" ] || fail "reminder for booking 1 not CANCELLED"
pass "cancelled + refunded, charged once, seats released, events processed, reminder cancelled"

step "6. Teardown"
docker compose down -v >/dev/null 2>&1
pass "stack removed, data wiped"

echo; echo "${G}${B}ALL CHECKS PASSED${N}"
