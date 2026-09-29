package com.naua_security_mirage.app.data.supabase

object OAuthCallbackPolicy {

    sealed interface Verdict {

        data class Accept(val codeVerifier: String) : Verdict

        data class Reject(val reason: String) : Verdict
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
