#!/usr/bin/env bash
# Verify a deployed relay is running a build that includes the attachment (blob)
# feature — i.e. NOT a pre-0.7.0-beta build. Run this after redeploying the
# relay, or any time attachments misbehave, to confirm the live server has the
# blob routes and the raised body limit.
#
# Usage:
#   scripts/verify-relay-build.sh [BASE_URL]
#   BASE_URL defaults to https://relay.sublemonable.com
#
# What it checks (all UNAUTHENTICATED probes — safe, mutate nothing):
#   1. /healthz responds.
#   2. Blob route PRESENT: POST /api/v1/blobs with a tiny body must return 401
#      (route exists, behind auth) — NOT 404 (route absent = stale pre-blob build).
#   3. Blob redeem route PRESENT: POST /api/v1/blobs/redeem tiny body must reach
#      the handler (400 bad_token), NOT a router 404.
#   4. Raised body limit LIVE: POST /api/v1/blobs ~1MB unauth must still be 401
#      (accepted past the old 512KiB cap), NOT 413.
#   5. Guard signature (new build): POST /api/v1/register >512KiB must return the
#      guard's {"error":"payload_too_large"} — the current build's signature.
#      A generic {"error":"error"} 413 at exactly 512KiB indicates the OLD build.
#
# Exit non-zero if any check fails. No secrets, no auth needed.
set -euo pipefail

BASE="${1:-https://relay.sublemonable.com}"
PASS=0; FAIL=0
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=$((FAIL+1)); }

# code_for METHOD PATH [BODYSIZE_BYTES]
code_for() {
  local method="$1" path="$2" size="${3:-0}"
  if [ "$size" -gt 0 ]; then
    head -c "$size" /dev/zero | tr '\0' 'a' \
      | curl -s -o /tmp/_relaybody -w '%{http_code}' -m 30 -X "$method" \
          -H 'Content-Type: application/json' --data-binary @- "$BASE$path"
  else
    curl -s -o /tmp/_relaybody -w '%{http_code}' -m 30 -X "$method" \
      -H 'Content-Type: application/json' -d '{}' "$BASE$path"
  fi
}
body() { cat /tmp/_relaybody 2>/dev/null; }

echo "Verifying relay build at: $BASE"
echo "────────────────────────────────────────────────────────────"

# 1. healthz
c=$(curl -s -o /tmp/_relaybody -w '%{http_code}' -m 20 "$BASE/healthz" || echo 000)
[ "$c" = 200 ] && ok "healthz 200" || bad "healthz returned $c (expected 200)"

# 2. blob upload route present (401, not 404)
c=$(code_for POST /api/v1/blobs)
case "$c" in
  401) ok "POST /api/v1/blobs -> 401 (route present, behind auth)";;
  404) bad "POST /api/v1/blobs -> 404 — blob route ABSENT (STALE pre-0.7.0-beta build). Redeploy required.";;
  *)   bad "POST /api/v1/blobs -> $c (expected 401; body=$(body))";;
esac

# 3. blob redeem route present (handler 400, not router 404)
c=$(code_for POST /api/v1/blobs/redeem)
if [ "$c" = 400 ]; then ok "POST /api/v1/blobs/redeem -> 400 (handler reached)"
elif [ "$c" = 404 ] && grep -q 'not_found' /tmp/_relaybody; then ok "POST /api/v1/blobs/redeem -> 404 not_found (handler reached)"
else bad "POST /api/v1/blobs/redeem -> $c body=$(body) (expected handler 400/404 not_found; a router 404 {\"error\":\"error\"} = route absent)"
fi

# 4. raised body limit: ~1MB blob upload not 413
c=$(code_for POST /api/v1/blobs 1100000)
case "$c" in
  401) ok "POST /api/v1/blobs ~1MB -> 401 (raised body limit live; not 413)";;
  413) bad "POST /api/v1/blobs ~1MB -> 413 — body limit NOT raised (stale build, or upstream proxy cap <1MB).";;
  *)   bad "POST /api/v1/blobs ~1MB -> $c (expected 401; body=$(body))";;
esac

# 5. guard signature on a non-blob route (new build says payload_too_large)
c=$(code_for POST /api/v1/register 600000)
if [ "$c" = 413 ] && grep -q 'payload_too_large' /tmp/_relaybody; then
  ok "POST /api/v1/register 600KiB -> 413 payload_too_large (current-build guard signature)"
elif [ "$c" = 413 ]; then
  bad "POST /api/v1/register 600KiB -> 413 but body=$(body) (expected payload_too_large; a generic {\"error\":\"error\"} = OLD build's global BodyLimit)"
else
  bad "POST /api/v1/register 600KiB -> $c (expected 413 payload_too_large; body=$(body))"
fi

echo "────────────────────────────────────────────────────────────"
echo "  $PASS passed, $FAIL failed"
rm -f /tmp/_relaybody
[ "$FAIL" -eq 0 ]
