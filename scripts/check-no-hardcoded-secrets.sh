

set -euo pipefail

fail=0
fail_with() {
  echo "::error::$1"
  fail=1
}

echo "== 1. no secret-bearing file may be tracked =="
for f in local.properties key.properties google-services.json app/google-services.json; do
  if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
    fail_with "$f is tracked. It must stay untracked and ignored."
  fi
done
if git ls-files | grep -qE '\.(jks|keystore|p12|pem)$'; then
  fail_with "a keystore is tracked in this repository."
fi

echo "== 2. build fields must read getSecret(), never a literal =="

offenders=$(tr '\n' ' ' < app/build.gradle.kts \
  | grep -oE 'buildConfigField\([^)]*\)' \
  | grep -v 'getSecret' \
  | grep -v 'LOGS_ENABLED' || true)
if [ -n "$offenders" ]; then
  fail_with "buildConfigField with a literal value in app/build.gradle.kts:
$offenders"
fi

echo "== 3. CI must not echo a secret into the log =="
if grep -rnE 'echo.*\$\{\{ *secrets\.' .github/workflows/ >/dev/null 2>&1; then
  offenders=$(grep -rnE 'echo.*\$\{\{ *secrets\.' .github/workflows/ || true)
  fail_with "a workflow prints a secret:
$offenders"
fi

echo "== 4. edge functions must read secrets from the environment only =="
if grep -rnE 'Deno\.env\.get\(' supabase/functions/ >/dev/null 2>&1; then
  if grep -rnE '(const|let) +[A-Z_]*(PASSWORD|SECRET|KEY|TOKEN)[A-Z_]* *= *"[^\"]{6,}"' supabase/functions/ >/dev/null 2>&1; then
    offenders=$(grep -rnE '(const|let) +[A-Z_]*(PASSWORD|SECRET|KEY|TOKEN)[A-Z_]* *= *"[^\"]{6,}"' supabase/functions/ || true)
    fail_with "an edge function hardcodes a secret:
$offenders"
  fi
fi

echo "== 5. an edge function must not import across function directories =="

if grep -rnE 'from "\.\./[a-z-]+/' supabase/functions/ >/dev/null 2>&1; then
  offenders=$(grep -rnE 'from "\.\./[a-z-]+/' supabase/functions/ || true)
  fail_with "cross-function import will not deploy:
$offenders"
fi

echo "== 6. no shared client identifier may be compiled in =="

if grep -rnE 'FRANCE_DEFAULT_UUID|DEFAULT_CLIENT_UUID' app/build.gradle.kts app/src/main >/dev/null 2>&1; then
  offenders=$(grep -rnE 'FRANCE_DEFAULT_UUID|DEFAULT_CLIENT_UUID' app/build.gradle.kts app/src/main || true)
  fail_with "a shared France client identifier is compiled in:
$offenders"
fi

echo "== 7. no identity may be derived from an unverified token =="

if grep -rnE 'Base64\.decode\([^)]*parts\[1\]' app/src/main >/dev/null 2>&1; then
  offenders=$(grep -rnE 'Base64\.decode\([^)]*parts\[1\]' app/src/main || true)
  fail_with "identity is taken from an unverified token payload:
$offenders"
fi

if grep -rn 'params\["access_token"\]' app/src/main >/dev/null 2>&1; then
  offenders=$(grep -rn 'params\["access_token"\]' app/src/main || true)
  fail_with "the OAuth callback must not read a token out of the deep link:
$offenders"
fi

echo "== 8. session tokens must not live in a plain preferences file =="

if grep -rn 'getSharedPreferences(PREFS_NAME,' app/src/main >/dev/null 2>&1; then
  offenders=$(grep -rn 'getSharedPreferences(PREFS_NAME,' app/src/main || true)
  fail_with "session tokens are opened without encryption:
$offenders"
fi

echo "== 9. the window must opt out of screenshots =="
if ! grep -rq 'FLAG_SECURE' app/src/main; then
  fail_with "FLAG_SECURE is not set; the account screen is readable in screenshots and recents"
fi

echo "== 10. a TLS bypass must not be reachable from an API client =="

probe='app/src/main/java/com/naua_security_mirage/app/vpn/MirageVpnService.kt'
others=$(grep -rl 'checkServerTrusted' app/src/main 2>/dev/null | grep -v "^$probe\$" || true)
if [ -n "$others" ]; then
  fail_with "a TrustManager is implemented outside the reachability probe:
$others"
fi
if grep -rn 'SslErrorHandler' app/src/main >/dev/null 2>&1; then
  offenders=$(grep -rn 'SslErrorHandler' app/src/main || true)
  fail_with "a TLS error is being waved through:
$offenders"
fi

echo "== 11. no cleartext traffic and no trust-all anchors =="
if ! grep -q 'cleartextTrafficPermitted="false"' app/src/main/res/xml/network_security_config.xml; then
  fail_with "cleartext traffic is not explicitly refused in network_security_config.xml"
fi
if grep -q 'src="user"' app/src/main/res/xml/network_security_config.xml; then
  fail_with "user-installed certificates are trusted"
fi

echo "== 11. no cleartext traffic and no trust-all anchors =="
if ! grep -q 'cleartextTrafficPermitted="false"' app/src/main/res/xml/network_security_config.xml; then
  fail_with "cleartext traffic is not explicitly refused in network_security_config.xml"
fi
if grep -q 'src="user"' app/src/main/res/xml/network_security_config.xml; then
  fail_with "user-installed certificates are trusted"
fi

if [ "$fail" -ne 0 ]; then
  echo ""
  echo "Secret guard FAILED."
  exit 1
fi

echo ""
echo "Secret guard passed."
