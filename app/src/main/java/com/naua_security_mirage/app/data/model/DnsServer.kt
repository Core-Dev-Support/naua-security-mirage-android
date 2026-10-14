package com.naua_security_mirage.app.data.model

data class DnsServer(
    val id: String,
    val url: String,
    val bootstrapIps: List<String> = emptyList()
) {
    companion object {
        const val DEFAULT_ID = "cloudflare"

        val BUILT_IN = listOf(
            DnsServer(
                id = "cloudflare",
                url = "https://cloudflare-dns.com/dns-query",
                bootstrapIps = listOf("1.1.1.1", "1.0.0.1")
            ),
            DnsServer(
                id = "google",
                url = "https://dns.google/dns-query",
                bootstrapIps = listOf("8.8.8.8", "8.8.4.4")
            ),
            DnsServer(
                id = "quad9",
                url = "https://dns.quad9.net/dns-query",
                bootstrapIps = listOf("9.9.9.9", "149.112.112.112")
            ),
            DnsServer(
                id = "adguard",
                url = "https://dns.adguard-dns.com/dns-query",
                bootstrapIps = listOf("94.140.14.14", "94.140.15.15")
            ),
            DnsServer(
                id = "mullvad",
                url = "https://dns.mullvad.net/dns-query",
                bootstrapIps = listOf("194.242.2.2")
            ),
            DnsServer(
                id = "yandex",
                url = "https://common.dot.dns.yandex.net/dns-query",
                bootstrapIps = listOf("77.88.8.8", "77.88.8.1")
            )
        )

        fun byId(id: String): DnsServer? = BUILT_IN.firstOrNull { it.id == id }
    }
}
