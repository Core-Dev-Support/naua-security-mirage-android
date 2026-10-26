# Subscription and VPN live checks

## What was fixed

- Supabase refresh no longer uses `select=*`; it requests the columns the Android client actually understands. This keeps the client working while the production table is being migrated.
- A failed/unauthorized/malformed 3X-UI or Supabase response cannot clear an active paid cache.
- Supabase `refresh_token` is persisted and an expired access token is retried once.
- YooMoney notifications verify the signature, resolve the real account email, store the actual 3X-UI client UUID, use inbound `2` by default, and use `operation_id` for idempotency.
- Free mode tries every returned free node and requires a real data-plane probe before reporting `CONNECTED`.
- Xray empty/`{}` responses are not treated as success; failed candidates close the TUN instead of leaving a dead blocking tunnel.
- The device log showed the bundled LibXray converter rejecting the France outbound and the routing rule putting the proxy hostname into an `ip` field. The app now uses the validated manual outbound by default and emits an IP-only anti-loop rule (hostnames use `domain:`).

## Apply the database migration once

Run `supabase/migrations/20260924000000_fix_subscriptions.sql` in the Supabase SQL editor. It preserves existing rows and adds `flow` and `operation_id` before tightening RLS.

Deploy `supabase/functions/yoomoney-webhook/` after setting these Edge Function secrets:

- `SUPABASE_URL`
- `SUPABASE_SERVICE_ROLE_KEY`
- `THREE_X_UI_BASE_URL`
- `THREE_X_UI_USERNAME` (the legacy `THREE_X_UI_USER` is also accepted)
- `THREE_X_UI_PASSWORD` (the legacy `THREE_X_UI_PASS` is also accepted)
- `THREE_X_UI_INBOUND_ID` (use `2` unless the live panel is deliberately different)
- `YOOMONEY_SECRET`
- `YOOMONEY_EXPECTED_AMOUNT_RUB` (default `30`; set this to the exact amount sent by YooMoney if the legacy notification uses a discounted value)
- `YOOMONEY_AMOUNT_TOLERANCE_KOPECKS` (default `0`; keep this at zero unless the processor contract explicitly allows a discount)
- `FRANCE_HOST`, `FRANCE_PORT`, `FRANCE_PBK`, `FRANCE_SNI`, `FRANCE_SID`, `FRANCE_SPX`, `FRANCE_FINGERPRINT`

The webhook accepts only `notification_type=payout`, requires RUB, and compares the amount in integer kopecks. Android/desktop clients no longer write `subscriptions`; the service-role webhook is the only writer.

## CI checks

The Android workflow now runs:

1. Deno type-check and contract tests for the YooMoney function.
2. Android unit tests for expiry parsing, cache decisions, and VLESS transport parsing.
3. The signed release build.

The read-only production check is manual: run the **Live subscription check** workflow and provide either the account email or auth user UUID. It checks the production columns, the Supabase row, 3X-UI login, the selected inbound, and a matching enabled client without printing credentials or VLESS links.
