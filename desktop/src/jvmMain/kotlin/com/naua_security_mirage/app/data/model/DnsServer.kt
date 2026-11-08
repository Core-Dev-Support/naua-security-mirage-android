package com.naua_security_mirage.app.data.model

data class DnsServer(
    val id: String,
    val url: String,
    val bootstrapIps: List<String> = emptyList(),
    val jsonUrl: String? = null
) {
    companion object {
        const val DEFAULT_ID = "cloudflare"
        const val AUTO_ID = "auto"
        const val PLAIN_ID = "plain"
        const val CUSTOM_ID = "custom"

        val BUILT_IN = listOf(
            DnsServer("cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1"), "https://cloudflare-dns.com/dns-query"),
            DnsServer("google", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4"), "https://dns.google/resolve"),
            DnsServer("quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
            DnsServer("adguard", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15"), "https://dns.adguard-dns.com/resolve"),
            DnsServer("mullvad", "https://dns.mullvad.net/dns-query", listOf("194.242.2.2")),
            DnsServer("yandex", "https://common.dot.dns.yandex.net/dns-query", listOf("77.88.8.8", "77.88.8.1"))
        )

        fun byId(id: String): DnsServer? = BUILT_IN.firstOrNull { it.id == id }
    }
}
