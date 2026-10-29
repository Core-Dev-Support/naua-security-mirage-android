package com.naua_security_mirage.app.util

/**
 * Pure-Kotlin version comparison, kept out of AppUpdateManager so it can be
 * covered by JVM unit tests without Android's Looper/Handler initialization.
 */
object VersionComparator {

    fun isNewer(latestTag: String, currentVersion: String): Boolean {
        val latest = parse(latestTag)
        val current = parse(currentVersion)

        val maxLen = maxOf(latest.numbers.size, current.numbers.size)
        for (i in 0 until maxLen) {
            val l = latest.numbers.getOrElse(i) { 0 }
            val c = current.numbers.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }

        return when {
            latest.preRelease == null && current.preRelease != null -> true
            latest.preRelease != null && current.preRelease == null -> false
            latest.preRelease != null && current.preRelease != null ->
                comparePreRelease(latest.preRelease, current.preRelease) > 0
            else -> false
        }
    }

    private data class ParsedVersion(val numbers: List<Int>, val preRelease: List<String>?)

    private fun parse(raw: String): ParsedVersion {
        val clean = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val main = clean.substringBefore('-')
        val numbers = main.split(".").mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0) }
        val preRelease = clean.substringAfter('-', "")
            .takeIf { it.isNotBlank() }
            ?.split('.', '-')
            ?.filter { it.isNotBlank() }
        return ParsedVersion(numbers, preRelease)
    }

    private fun comparePreRelease(latest: List<String>, current: List<String>): Int {
        val maxLen = maxOf(latest.size, current.size)
        for (i in 0 until maxLen) {
            val l = latest.getOrNull(i) ?: return -1
            val c = current.getOrNull(i) ?: return 1
            val lNumber = l.toIntOrNull()
            val cNumber = c.toIntOrNull()
            val comparison = if (lNumber != null && cNumber != null) {
                lNumber.compareTo(cNumber)
            } else {
                l.compareTo(c)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }
}
