package com.naua_security_mirage.app.vpn

import com.naua_security_mirage.app.data.model.VlessServer

object AiRegionBlock {

    private val BLOCKED = listOf(
        "\uD83C\uDDF7\uD83C\uDDFA",
        "\uD83C\uDDE7\uD83C\uDDFE",
        "\uD83C\uDDF0\uD83C\uDDFF",
        "\uD83C\uDDFA\uD83C\uDDF8",
        "\uD83C\uDDF9\uD83C\uDDF2",
        "\uD83C\uDDF0\uD83C\uDDFC",
        "\uD83C\uDDEF\uD83C\uDDF5",
        "\uD83C\uDDE8\uD83C\uDDF3",
        "\uD83C\uDDE7\uD83C\uDDFE",
        "\uD83C\uDDFA\uD83C\uDDF8",
        "\uD83C\uDDE8\uD83C\uDDF6",
        "\uD83C\uDDF5\uD83C\uDDD6",
        "россия", "russia",
        "беларус", "belarus",
        "казахстан", "kazakhstan",
        "узбекистан", "uzbekistan",
        "туркменистан", "turkmenistan",
        "киргизия", "kyrgyzstan",
        "таджикистан", "tajikistan",
        "китай", "china",
        "иран", "iran",
        "куба", "cuba",
        "венесуэл", "venezuela",
        "северная корея", "north korea"
    )

    fun isBlocked(server: VlessServer): Boolean {
        val tag = server.tag.lowercase()
        return BLOCKED.any { tag.contains(it) }
    }

    fun filter(servers: List<VlessServer>): List<VlessServer> {
        val allowed = servers.filterNot { isBlocked(it) }
        return if (allowed.isEmpty()) servers else allowed
    }
}