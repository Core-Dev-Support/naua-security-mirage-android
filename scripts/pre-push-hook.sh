#!/bin/sh
# Git pre-push secret scanner hook

echo "[SECRET SCANNER] Checking outgoing commits before push to GitHub..."

# 1. Check against local.properties values if present (dynamic check against developer's actual secrets)
if [ -f "local.properties" ]; then
    FORBIDDEN_VALUES=$(grep -E '^(THREE_X_UI_PASSWORD|FRANCE_HOST|FRANCE_PBK|FRANCE_SID|SUPABASE_ANON_KEY|YOOMONEY_WALLET)=' local.properties | cut -d'=' -f2- | tr -d '\r' | sed 's/\\//g' | grep -v '^$')
    for val in $FORBIDDEN_VALUES; do
        if [ ${#val} -gt 5 ]; then
            MATCH=$(git log -p "$REMOTE_SHA..HEAD" -- . ':!app/src/main/assets/geo/*' ':!desktop/Core/geo*' ':!scripts/*' ':!.github/*' 2>/dev/null | grep -E "^\+[^+]" | grep -F "$val")
            if [ -n "$MATCH" ]; then
                echo ""
                echo "================================================================================"
                echo "❌ [SECURITY ERROR] PUSH TO GITHUB BLOCKED: Secret value from local.properties detected!"
                echo "--------------------------------------------------------------------------------"
                echo "$MATCH"
                echo "--------------------------------------------------------------------------------"
                echo "Push aborted to prevent leaking sensitive data to public repository."
                echo "Remove the secrets before pushing to GitHub!"
                echo "================================================================================"
                echo ""
                exit 1
            fi
        fi
    done
fi

# 2. General heuristic patterns (catch hardcoded credential assignments)
GENERIC_MATCH=$(git log -p "$REMOTE_SHA..HEAD" -- . ':!app/src/main/assets/geo/*' ':!desktop/Core/geo*' ':!scripts/*' ':!.github/*' 2>/dev/null | grep -E "^\+[^+]" | grep -E '(const\s+val|val|var)\s+THREE_X_UI_PASSWORD\s*=\s*"[^"]{4,}"|(const\s+val|val|var)\s+FRANCE_PBK\s*=\s*"[^"]{10,}"|eyJhbGciOi')

if [ -n "$GENERIC_MATCH" ]; then
    echo ""
    echo "================================================================================"
    echo "❌ [SECURITY ERROR] PUSH TO GITHUB BLOCKED: Hardcoded credential assignment detected!"
    echo "--------------------------------------------------------------------------------"
    echo "$GENERIC_MATCH"
    echo "--------------------------------------------------------------------------------"
    echo "Push aborted to prevent leaking sensitive data to public repository."
    echo "Remove the secrets before pushing to GitHub!"
    echo "================================================================================"
    echo ""
    exit 1
fi

echo "✅ [SECRET SCANNER] OK: No plaintext secrets in outgoing commits."
exit 0
