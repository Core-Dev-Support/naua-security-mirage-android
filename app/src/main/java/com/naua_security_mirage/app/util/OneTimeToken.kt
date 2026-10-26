package com.naua_security_mirage.app.util

import java.util.UUID

/** Одноразовый пропуск для действий, которые запускаются через exported Activity. */
object OneTimeToken {
    @Volatile
    private var current: String? = null

    fun issue(): String = UUID.randomUUID().toString().also { current = it }

    fun consume(candidate: String?): Boolean {
        if (candidate.isNullOrEmpty() || candidate != current) return false
        current = null
        return true
    }
}
