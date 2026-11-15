package com.v2ray.ang.firefox.hub

/**
 * One-Tap Recipes — a few named presets that set the engine up for what the person is doing, so they
 * do not have to know what "transport" or "hello_split" mean. Each recipe is a declarative spec; the
 * ViewModel merges it into the engine's live settings and writes them back. Keeping the spec pure
 * means the presets can be tested without a phone.
 */
object FirefoxRecipes {

    enum class Recipe(val id: String) {
        /** Smooth video: a stable HTTP/2 tunnel, DNS that rescues itself. */
        STREAMING("streaming"),

        /** Lowest latency: race every location and take the fastest. */
        GAMING("gaming"),

        /** Encrypted DNS only, ClientHello split, no plain-DNS fallback. */
        PRIVACY("privacy"),

        /** Spend the allowance slowly: hold a high bar before switching accounts. */
        SAVE_QUOTA("save_quota"),

        /** Heavily filtered network: every DNS path, Hello split, and the relay rescue armed. */
        BYPASS("bypass"),
    }

    /** The settings a recipe changes; null fields are left as they are. */
    data class Spec(
        val transport: String? = null,
        val country: String? = null,
        val dnsServers: List<String>? = null,
        val fallbackToSystem: Boolean? = null,
        val helloSplit: String? = null,
        val switchThresholdPercent: Double? = null,
        val sharedRelayEnabled: Boolean? = null,
    )

    private val DOH_ONLY = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/dns-query",
        "https://9.9.9.9/dns-query",
    )

    private val EVERY_PATH = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/dns-query",
        "https://9.9.9.9/dns-query",
        "tls://1.1.1.1",
        "tls://8.8.8.8",
        "udp://1.1.1.1",
        "udp://8.8.8.8",
        "udp://9.9.9.9",
    )

    fun spec(recipe: Recipe): Spec = when (recipe) {
        Recipe.STREAMING -> Spec(
            transport = "h2",
            fallbackToSystem = true,
            helloSplit = "off",
        )
        Recipe.GAMING -> Spec(
            transport = "h2",
            country = "fastest",
            fallbackToSystem = true,
        )
        Recipe.PRIVACY -> Spec(
            dnsServers = DOH_ONLY,
            fallbackToSystem = false,
            helloSplit = "sni",
        )
        Recipe.SAVE_QUOTA -> Spec(
            switchThresholdPercent = 95.0,
        )
        Recipe.BYPASS -> Spec(
            dnsServers = EVERY_PATH,
            fallbackToSystem = true,
            helloSplit = "sni",
            sharedRelayEnabled = true,
        )
    }

    fun byId(id: String): Recipe? = Recipe.entries.firstOrNull { it.id == id }

    /**
     * The config patch a recipe writes, grouped by the engine's config sections.
     *
     * The engine nests transport/country under "proxy", hello_split/shared_relay_enabled under
     * "network", switch_threshold_percent under "accounts", and the DNS fields under "dns". A flat
     * top-level key is rejected as an unknown field, so each value is placed in its own section.
     * Keeping this pure (no Android JSON) lets the nesting be unit-tested without a phone.
     */
    fun patch(recipe: Recipe): Map<String, Map<String, Any>> {
        val spec = spec(recipe)
        val proxy = LinkedHashMap<String, Any>()
        val network = LinkedHashMap<String, Any>()
        val accounts = LinkedHashMap<String, Any>()
        val dns = LinkedHashMap<String, Any>()
        spec.transport?.let { proxy["transport"] = it }
        spec.country?.let { proxy["country"] = it }
        spec.helloSplit?.let { network["hello_split"] = it }
        spec.sharedRelayEnabled?.let { network["shared_relay_enabled"] = it }
        spec.switchThresholdPercent?.let { accounts["switch_threshold_percent"] = it }
        spec.dnsServers?.let { dns["servers"] = it }
        spec.fallbackToSystem?.let { dns["fallback_to_system"] = it }
        val out = LinkedHashMap<String, Map<String, Any>>()
        if (proxy.isNotEmpty()) out["proxy"] = proxy
        if (network.isNotEmpty()) out["network"] = network
        if (accounts.isNotEmpty()) out["accounts"] = accounts
        if (dns.isNotEmpty()) out["dns"] = dns
        return out
    }
}
