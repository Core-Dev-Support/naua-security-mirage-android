#!/usr/bin/env bash
set -Eeuo pipefail

# Read-only production contract check. It never prints service credentials,
# cookies, VLESS links, Reality keys, or complete client UUIDs.

fail() {
  printf 'LIVE_CHECK_FAILED: %s\n' "$*" >&2
  exit 1
}

for name in SUPABASE_URL SUPABASE_SERVICE_ROLE_KEY THREE_X_UI_BASE_URL THREE_X_UI_USERNAME THREE_X_UI_PASSWORD; do
  if [[ -z "${!name:-}" ]]; then
    fail "$name is not configured"
  fi
done

command -v curl >/dev/null || fail "curl is required"
command -v jq >/dev/null || fail "jq is required"

CHECK_EMAIL="${CHECK_EMAIL:-}"
CHECK_USER_ID="${CHECK_USER_ID:-}"
CHECK_CLIENT_UUID="${CHECK_CLIENT_UUID:-}"
THREE_X_UI_INBOUND_ID="${THREE_X_UI_INBOUND_ID:-2}"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

supabase_request() {
  local output="$1"
  shift
  curl -sS -o "$output" -w '%{http_code}' \
    -H "apikey: ${SUPABASE_SERVICE_ROLE_KEY}" \
    -H "Authorization: Bearer ${SUPABASE_SERVICE_ROLE_KEY}" \
    "$@"
}

# This intentionally selects the columns used by the app and webhook. A 42703
# here means the production migration has not been applied yet.
schema_code="$(supabase_request "$workdir/schema.json" \
  --get "${SUPABASE_URL%/}/rest/v1/subscriptions" \
  --data-urlencode 'select=flow,operation_id' \
  --data-urlencode 'limit=1')"
[[ "$schema_code" == 2* ]] || fail "Supabase subscriptions schema check returned HTTP ${schema_code}"
jq -e 'type == "array"' "$workdir/schema.json" >/dev/null || fail "Supabase schema response is not JSON"

subscription_args=()
if [[ -n "$CHECK_EMAIL" ]]; then
  subscription_args+=(--data-urlencode "email=eq.${CHECK_EMAIL}")
elif [[ -n "$CHECK_USER_ID" ]]; then
  subscription_args+=(--data-urlencode "user_id=eq.${CHECK_USER_ID}")
else
  fail "set CHECK_EMAIL or CHECK_USER_ID for a subscription-specific check"
fi

subscription_code="$(supabase_request "$workdir/subscriptions.json" \
  --get "${SUPABASE_URL%/}/rest/v1/subscriptions" \
  "${subscription_args[@]}" \
  --data-urlencode 'select=user_id,email,is_active,plan,paid_until,client_uuid,operation_id,updated_at')"
[[ "$subscription_code" == 2* ]] || fail "Supabase subscription lookup returned HTTP ${subscription_code}"
jq -e 'type == "array" and length == 1' "$workdir/subscriptions.json" >/dev/null \
  || fail "expected exactly one subscription row"

jq -r '.[] |
  "subscription: active=\(.is_active) plan=\(.plan) paid_until=\(.paid_until // "null") " +
  "has_client_uuid=\(.client_uuid != null) operation_id_present=\(.operation_id != null) " +
  "user_suffix=\((.user_id // "")[-8:]) client_suffix=\(((.client_uuid // "")[-8:]))"' \
  "$workdir/subscriptions.json"

active_in_supabase="$(jq -r '.[0].is_active == true and (.[0].paid_until != null)' "$workdir/subscriptions.json")"
[[ "$active_in_supabase" == "true" ]] || fail "the selected Supabase row is not active or has no paid_until"

# Authenticate to 3X-UI and inspect the configured inbound without mutating it.
login_code="$(curl -sS -o "$workdir/login.json" -c "$workdir/cookies.txt" -w '%{http_code}' \
  -X POST "${THREE_X_UI_BASE_URL%/}/login" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode "username=${THREE_X_UI_USERNAME}" \
  --data-urlencode "password=${THREE_X_UI_PASSWORD}")"
[[ "$login_code" == 2* ]] || fail "3X-UI login returned HTTP ${login_code}"
jq -e '.success == true' "$workdir/login.json" >/dev/null || fail "3X-UI login did not return success=true"

inbound_code="$(curl -sS -o "$workdir/inbound.json" -b "$workdir/cookies.txt" -w '%{http_code}' \
  "${THREE_X_UI_BASE_URL%/}/panel/api/inbounds/get/${THREE_X_UI_INBOUND_ID}")"
[[ "$inbound_code" == 2* ]] || fail "3X-UI inbound ${THREE_X_UI_INBOUND_ID} returned HTTP ${inbound_code}"
jq -e '.success == true and (.obj.settings | type == "string")' "$workdir/inbound.json" >/dev/null \
  || fail "3X-UI inbound response is malformed"

jq -r '.obj.settings' "$workdir/inbound.json" > "$workdir/settings.json"
jq -e '(.clients | type) == "array"' "$workdir/settings.json" >/dev/null || fail "3X-UI clients array is missing"

if [[ -n "$CHECK_CLIENT_UUID" ]]; then
  jq --arg uuid "$CHECK_CLIENT_UUID" \
    '[.clients[]? | select((.id // "") == $uuid)]' \
    "$workdir/settings.json" > "$workdir/matches.json"
elif [[ -n "$CHECK_EMAIL" ]]; then
  jq --arg email "$CHECK_EMAIL" \
    '[.clients[]? | select(((.email // "") | ascii_downcase) == ($email | ascii_downcase))]' \
    "$workdir/settings.json" > "$workdir/matches.json"
else
  jq '[.clients[]?]' "$workdir/settings.json" > "$workdir/matches.json"
fi

match_count="$(jq 'length' "$workdir/matches.json")"
[[ "$match_count" =~ ^[0-9]+$ && "$match_count" -gt 0 ]] || fail "no matching 3X-UI client found"
active_count="$(jq '[.[] | select((.enable // true) == true)] | length' "$workdir/matches.json")"
[[ "$active_count" -gt 0 ]] || fail "matching 3X-UI client exists but is disabled"
printf '3X-UI: inbound=%s matches=%s active=%s\n' "$THREE_X_UI_INBOUND_ID" "$match_count" "$active_count"

printf 'LIVE_CHECK_OK\n'
