package com.naua_security_mirage.app.data.supabase

object OAuthCallbackPolicy {

    sealed interface Verdict {

        data class Accept(val codeVerifier: String) : Verdict

        data class Reject(val reason: String) : Verdict
    }

    /** Попытка входа: своё состояние, время старта и PKCE-верификатор. */
    data class Attempt(val state: String, val issuedAt: Long, val verifier: String)

    /**
     * Выбирает попытку входа, к которой относится ответ провайдера.
     *
     * Попыток может быть несколько: вход можно начать заново, пока старый ещё
     * открыт в браузере, и тогда вернётся код от первой попытки. Если провайдер
     * прислал state — берём попытку с таким state, иначе последнюю свежую.
     */
    fun pickAttempt(
        attempts: List<Attempt>,
        returnedState: String?,
        now: Long,
        stateTtlMs: Long
    ): Attempt? {
        val fresh = attempts.filter { now - it.issuedAt in 0..stateTtlMs }
        if (fresh.isEmpty()) return null
        if (!returnedState.isNullOrBlank()) {
            fresh.firstOrNull { it.state == returnedState }?.let { return it }
        }
        return fresh.lastOrNull()
    }

    fun evaluate(
        expectedState: String?,
        issuedAt: Long,
        now: Long,
        returnedState: String?,
        code: String?,
        verifier: String?,
        stateTtlMs: Long
    ): Verdict {
        if (expectedState.isNullOrBlank()) return Verdict.Reject("нет начатого входа")
        if (now - issuedAt !in 0..stateTtlMs) return Verdict.Reject("вход устарел")

        // The provider does not echo our state back in this flow, so a missing
        // state is normal. A present-but-different state is still rejected, and
        // the stored PKCE verifier below proves this device started the flow.
        if (!returnedState.isNullOrBlank() && returnedState != expectedState) {
            return Verdict.Reject("state не совпадает")
        }
        if (code.isNullOrBlank()) return Verdict.Reject("нет кода авторизации")
        if (verifier.isNullOrBlank()) return Verdict.Reject("нет PKCE-верификатора")
        return Verdict.Accept(verifier)
    }
}

object PkceVerifier {

    const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun fromBytes(random: ByteArray): String {
        val sb = StringBuilder(random.size)
        for (b in random) {

            sb.append(ALPHABET[(b.toInt() and 0xFF) % ALPHABET.length])
        }
        return sb.toString()
    }
}

object FranceAccessPolicy {
    private val UUID_PATTERN =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun mayBuildPaidTunnel(serverIssuedUuid: String?): Boolean {
        val raw = serverIssuedUuid?.trim().orEmpty()
        if (raw.isEmpty()) return false

        val candidate = if (raw.startsWith("vless://")) {
            raw.removePrefix("vless://").substringBefore('@').substringBefore('?')
        } else {
            raw
        }
        return UUID_PATTERN.matches(candidate)
    }
}
