#!/usr/bin/env bash
# Pings the paid Switzerland node and pushes its state to the node-status
# function. H1 does not run our former on-box watchdog, so GitHub Actions
# keeps the in-app "state" row fresh instead.
set -euo pipefail

: "${SUPABASE_URL:?SUPABASE_URL is required}"
: "${SUPABASE_ANON_KEY:?SUPABASE_ANON_KEY is required}"
: "${NODE_TOKEN:?NODE_TOKEN is required}"

HOST="${NODE_HOST:?NODE_HOST is required}"
STATE="down"
DETAIL="no TLS response"
if curl -fsS -o /dev/null -m 10 "https://${HOST}/"; then
  STATE="up"
  DETAIL=""
fi

NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
BODY=$(printf '{"node":"france","state":"%s","detail":"%s","core":"H1","changed_at":"%s"}' \
  "$STATE" "$DETAIL" "$NOW")

curl -fsS -X POST "${SUPABASE_URL}/functions/v1/node-status" \
  -H "Content-Type: application/json" \
  -H "apikey: ${SUPABASE_ANON_KEY}" \
  -H "x-node-token: ${NODE_TOKEN}" \
  -d "$BODY"
echo
