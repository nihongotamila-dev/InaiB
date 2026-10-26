#!/usr/bin/env sh
# Replaces the starter blocklist with a large hosts-format list.
# Usage: scripts/update_blocklist.sh [list-url]
# Default list: StevenBlack unified hosts (ads + malware). Other hosts-format lists work too.
set -e
URL="${1:-https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts}"
OUT="$(dirname "$0")/../app/src/main/assets/blocklist_hosts.txt"
TMP="$(mktemp)"
curl -fsSL "$URL" \
  | grep -E '^(0\.0\.0\.0|127\.0\.0\.1)[[:space:]]' \
  | awk '{print $2}' \
  | grep -v -E '^(localhost|localhost\.localdomain|local|broadcasthost|0\.0\.0\.0)$' \
  | sort -u > "$TMP"
cat "$(dirname "$OUT")/blocklist_hosts.txt" "$TMP" | grep -v '^#' | sort -u > "$OUT.new"
mv "$OUT.new" "$OUT"
rm -f "$TMP"
echo "Blocklist now has $(wc -l < "$OUT") domains: $OUT"
