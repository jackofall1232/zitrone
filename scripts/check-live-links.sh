#!/usr/bin/env bash
# Zitrone — Copyright (C) 2026 Zitrone contributors
# Licensed under the GNU Affero General Public License v3.0 or later.
# SPDX-License-Identifier: AGPL-3.0-only
#
# check-live-links.sh — verify the RELEASE-CRITICAL external links on the LIVE,
# RENDERED zitrone.app pages (not just the constants in website/src/lib/links.ts).
#
# WHY THIS EXISTS: a broken Tor mirror link once shipped because the beta page
# assembled the URL at render time (http://<onion>/zitrone-<version>.apk) while
# the mirror only serves a single page at its root — so linting links.ts would
# not have caught it. This checker fetches the actual deployed HTML and asserts
# the rendered URLs are well-formed and reachable.
#
# CHECKS:
#   onion-link-form       the onion address is rendered BARE — never immediately
#                         followed by "/<path>" (the exact regression class above)
#   github-release-assets every rendered GitHub release-download URL resolves 200
#   onion-mirror-live     the bare onion root is reachable over Tor and serves
#                         recognizable mirror content (HTTP 200 + "Zitrone"/"mirror")
#
# FLAGS:
#   --deep    also download the GitHub APK asset and compare its sha256 against
#             the checksum rendered on the beta page
#
# ENV:
#   TOR_SOCKS        SOCKS5 proxy for Tor (default 127.0.0.1:9050)
#   ALLOW_NO_TOR=1   downgrade onion-mirror-live to a non-fatal SKIP when no Tor
#                    SOCKS proxy is listening (still loud; use only off-Tor CI)
#
# WHEN IT RUNS: on every push to main touching website/** or this script (see
# .github/workflows/link-check.yml), and on demand via workflow_dispatch or by
# running `bash scripts/check-live-links.sh` locally. No extra deps — bash + curl.
set -uo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
BASE_URL="https://zitrone.app"
BETA_URL="$BASE_URL/download/beta"
DOWNLOAD_URL="$BASE_URL/download"
TOR_SOCKS="${TOR_SOCKS:-127.0.0.1:9050}"
ALLOW_NO_TOR="${ALLOW_NO_TOR:-0}"
DEEP=0
for arg in "$@"; do
  case "$arg" in
    --deep) DEEP=1 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# The published onion mirror address. Kept in sync with website/src/lib/links.ts
# (PUBLIC_MIRROR_ONION) — read from there when the repo is checked out so this
# survives an onion rotation without a code edit, with a hard-coded fallback for
# running the script standalone.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LINKS_TS="$SCRIPT_DIR/../website/src/lib/links.ts"
ONION_FALLBACK="wyymleg2e3mdhib4twyu7bgofyxbtoj52jfycc4ihqc7atapxyj3kuqd.onion"
ONION=""
if [ -f "$LINKS_TS" ]; then
  ONION="$(grep -oE '[a-z2-7]{56}\.onion' "$LINKS_TS" | head -n1 || true)"
fi
[ -n "$ONION" ] || ONION="$ONION_FALLBACK"

# ── Result tracking ───────────────────────────────────────────────────────────
declare -a CHECK_NAMES CHECK_STATES CHECK_NOTES
record() { CHECK_NAMES+=("$1"); CHECK_STATES+=("$2"); CHECK_NOTES+=("$3"); }
say() { printf '  %s\n' "$*"; }
hr()  { printf '%s\n' "────────────────────────────────────────────────────────────────────"; }

# ── Fetch the live rendered pages once ────────────────────────────────────────
echo "Zitrone live-link check — $(date -u '+%Y-%m-%d %H:%M:%SZ')"
echo "Onion address under test: $ONION"
hr
echo "Fetching live pages ..."
BETA_HTML="$(curl -sL --max-time 45 "$BETA_URL" || true)"
DOWNLOAD_HTML="$(curl -sL --max-time 45 "$DOWNLOAD_URL" || true)"
ALL_HTML="$BETA_HTML"$'\n'"$DOWNLOAD_HTML"

if [ -z "$BETA_HTML" ]; then
  say "WARNING: could not fetch $BETA_URL (empty response)"
fi

# ── CHECK 1: onion-link-form ──────────────────────────────────────────────────
echo
echo "[onion-link-form] the onion address must render BARE (no trailing /path)"
# Any occurrence of the onion address immediately followed by "/" + a path
# character is the regression we are guarding against.
ONION_RE="$(printf '%s' "$ONION" | sed 's/\./\\./g')"
bare_count="$(printf '%s' "$ALL_HTML" | grep -oE "$ONION_RE" | wc -l | tr -d ' ')"
bad_hits="$(printf '%s' "$ALL_HTML" | grep -oE "$ONION_RE/[^\"'< ]+" || true)"
if [ "$bare_count" -eq 0 ]; then
  say "FAIL: onion address not found in rendered pages at all"
  record "onion-link-form" "FAIL" "address not present on live pages"
elif [ -n "$bad_hits" ]; then
  say "FAIL: onion address rendered WITH a trailing path (the shipped-regression class):"
  printf '%s\n' "$bad_hits" | sed 's/^/        /'
  record "onion-link-form" "FAIL" "rendered with trailing /path"
else
  say "PASS: found $bare_count bare occurrence(s), none followed by /path"
  record "onion-link-form" "PASS" "$bare_count bare occurrence(s)"
fi

# ── CHECK 2: github-release-assets ────────────────────────────────────────────
echo
echo "[github-release-assets] every rendered GitHub release-download URL -> 200"
mapfile -t GH_URLS < <(printf '%s' "$ALL_HTML" \
  | grep -oE 'https://github\.com/[^"'"'"'<> \\]*releases/download/[^"'"'"'<> \\]*' \
  | sort -u)
if [ "${#GH_URLS[@]}" -eq 0 ]; then
  say "FAIL: no GitHub release-download URLs found on rendered pages"
  record "github-release-assets" "FAIL" "no release URLs rendered"
else
  gh_ok=1
  for url in "${GH_URLS[@]}"; do
    code="$(curl -sIL --max-time 60 -o /dev/null -w '%{http_code}' "$url" || echo 000)"
    if [ "$code" = "200" ]; then
      say "PASS 200  $url"
    else
      say "FAIL $code  $url"
      gh_ok=0
    fi
  done
  if [ "$gh_ok" -eq 1 ]; then
    record "github-release-assets" "PASS" "${#GH_URLS[@]} asset(s) -> 200"
  else
    record "github-release-assets" "FAIL" "one or more assets not 200"
  fi
fi

# ── CHECK 3: onion-mirror-live ────────────────────────────────────────────────
echo
echo "[onion-mirror-live] the bare onion root must be reachable over Tor"
socks_host="${TOR_SOCKS%%:*}"
socks_port="${TOR_SOCKS##*:}"
if ! (exec 3<>"/dev/tcp/$socks_host/$socks_port") 2>/dev/null; then
  say "############################################################"
  say "# SKIPPED (no Tor SOCKS proxy available) at $TOR_SOCKS"
  say "############################################################"
  if [ "$ALLOW_NO_TOR" = "1" ]; then
    say "ALLOW_NO_TOR=1 -> recording as non-fatal SKIP"
    record "onion-mirror-live" "SKIP" "no Tor proxy; ALLOW_NO_TOR=1"
  else
    say "no Tor proxy and ALLOW_NO_TOR not set -> recording as FAIL"
    record "onion-mirror-live" "FAIL" "no Tor SOCKS proxy at $TOR_SOCKS"
  fi
else
  exec 3>&- 3<&- 2>/dev/null || true
  onion_ok=0
  onion_note="unreachable after 3 attempts"
  for attempt in 1 2 3; do
    say "attempt $attempt/3 -> http://$ONION/ (via $TOR_SOCKS, 60s timeout)"
    body="$(curl -s --socks5-hostname "$TOR_SOCKS" --max-time 60 \
      -w $'\n__HTTP__%{http_code}' "http://$ONION/" || true)"
    code="${body##*__HTTP__}"
    html="${body%$'\n'__HTTP__*}"
    if [ "$code" = "200" ] && printf '%s' "$html" | grep -qiE 'zitrone|mirror'; then
      onion_ok=1
      onion_note="HTTP 200 with recognizable mirror content"
      break
    elif [ "$code" = "200" ]; then
      onion_note="HTTP 200 but content unrecognizable (no 'Zitrone'/'mirror')"
    else
      onion_note="HTTP $code (no response / not reachable)"
    fi
  done
  if [ "$onion_ok" -eq 1 ]; then
    say "PASS: $onion_note"
    record "onion-mirror-live" "PASS" "$onion_note"
  else
    say "FAIL: $onion_note"
    record "onion-mirror-live" "FAIL" "$onion_note"
  fi
fi

# ── CHECK 4 (optional): --deep APK checksum match ─────────────────────────────
if [ "$DEEP" -eq 1 ]; then
  echo
  echo "[deep-apk-checksum] rendered SHA-256 must match the downloaded GitHub APK"
  rendered_sha="$(printf '%s' "$BETA_HTML" | grep -oiE '[0-9a-f]{64}' | head -n1 || true)"
  apk_url="$(printf '%s\n' "${GH_URLS[@]:-}" | grep -iE '\.apk$' | head -n1 || true)"
  if [ -z "$rendered_sha" ] || [ -z "$apk_url" ]; then
    say "FAIL: missing rendered checksum or APK url (sha='${rendered_sha:-none}')"
    record "deep-apk-checksum" "FAIL" "missing checksum or apk url"
  else
    tmp="$(mktemp)"
    say "downloading $apk_url ..."
    if curl -sL --max-time 300 -o "$tmp" "$apk_url"; then
      got_sha="$(sha256sum "$tmp" | awk '{print $1}')"
      if [ "$got_sha" = "$rendered_sha" ]; then
        say "PASS: sha256 matches ($got_sha)"
        record "deep-apk-checksum" "PASS" "sha256 match"
      else
        say "FAIL: rendered=$rendered_sha downloaded=$got_sha"
        record "deep-apk-checksum" "FAIL" "sha256 mismatch"
      fi
    else
      say "FAIL: could not download APK asset"
      record "deep-apk-checksum" "FAIL" "download failed"
    fi
    rm -f "$tmp"
  fi
fi

# ── Summary table ─────────────────────────────────────────────────────────────
echo
hr
echo "SUMMARY"
hr
fail_total=0
for i in "${!CHECK_NAMES[@]}"; do
  state="${CHECK_STATES[$i]}"
  [ "$state" = "FAIL" ] && fail_total=$((fail_total + 1))
  printf '  %-6s  %-22s  %s\n' "$state" "${CHECK_NAMES[$i]}" "${CHECK_NOTES[$i]}"
done
hr
if [ "$fail_total" -eq 0 ]; then
  echo "RESULT: all checks passed"
  exit 0
else
  echo "RESULT: $fail_total check(s) FAILED"
  exit 1
fi
