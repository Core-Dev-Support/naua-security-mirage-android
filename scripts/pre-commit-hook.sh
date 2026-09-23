#!/bin/sh
# Git pre-commit secret scanner hook

echo "[SECRET SCANNER] Checking staged files for sensitive data..."

# 1. Check against local.properties values if present (dynamic check against developer's actual secrets)
if [ -f "local.properties" ]; then
    FORBIDDEN_VALUES=$(grep -E '^(THREE_X_UI_PASSWORD|FRANCE_HOST|FRANCE_PBK|FRANCE_SID|SUPABASE_ANON_KEY|YOOMONEY_WALLET)=' local.properties | cut -d'=' -f2- | tr -d '\r' | sed 's/\\//g' | grep -v '^$')
    for val in $FORBIDDEN_VALUES; do
        if [ ${#val} -gt 5 ]; then
            MATCH=$(git diff --cached -p -- . ':!app/src/main/assets/geo/*' ':!desktop/Core/geo*' ':!scripts/*' ':!.github/*' 2>/dev/null | grep -E "^\+[^+]" | grep -F "$val")
            if [ -n "$MATCH" ]; then
                echo ""
                echo "================================================================================"
                echo "❌ [SECURITY ERROR] COMMIT BLOCKED: Secret value from local.properties detected!"
                echo "--------------------------------------------------------------------------------"
                echo "$MATCH"
                echo "--------------------------------------------------------------------------------"
                echo "Reason: Passwords, tokens, keys and server IPs must NEVER be committed to Git!"
                echo "Use BuildConfig, System.getenv(), or local.properties instead."
                echo "================================================================================"
                echo ""
                exit 1
            fi
        fi
    done
fi

# 2. General heuristic patterns (catch hardcoded credential assignments)
GENERIC_MATCH=$(git diff --cached -p -- . ':!app/src/main/assets/geo/*' ':!desktop/Core/geo*' ':!scripts/*' ':!.github/*' 2>/dev/null | grep -E "^\+[^+]" | grep -E '(const\s+val|val|var)\s+THREE_X_UI_PASSWORD\s*=\s*"[^"]{4,}"|(const\s+val|val|var)\s+FRANCE_PBK\s*=\s*"[^"]{10,}"|eyJhbGciOi')

if [ -n "$GENERIC_MATCH" ]; then
    echo ""
    echo "================================================================================"
    echo "❌ [SECURITY ERROR] COMMIT BLOCKED: Hardcoded credential assignment detected!"
    echo "--------------------------------------------------------------------------------"
    echo "$GENERIC_MATCH"
    echo "--------------------------------------------------------------------------------"
    echo "================================================================================"
    echo ""
    exit 1
fi

echo "✅ [SECRET SCANNER] OK: No plaintext secrets in staged files."
exit 0
