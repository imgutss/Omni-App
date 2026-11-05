package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.security.MessageDigest

/**
 * Validated inputs and the loopback topology of one QUIC-family sidecar session.
 *
 * Three protocols Xray itself cannot speak ride a native client of their own that opens a SOCKS5
 * listener on the loopback; Xray dials that listener as an ordinary SOCKS outbound, exactly as it
 * dials GUTS, DNSTT and MasterDNS:
 *
 *  - TUIC v5 runs on a sing-box client; NaiveProxy uses the official Chromium binary;
 *  - ShadowQUIC runs on the official shadowquic client, tuned for gaming by default.
 *
 * The client is told where to protect its sockets through an abstract-namespace Unix socket (see
 * [SidecarProtectServer]); without it the client's own calls would be routed back into the VPN it
 * feeds. Everything secret stays in the profile and in the private session directory, never on a
 * command line.
 */
data class QuicCore(
    val engine: Engine,
    val port: Int,
    val profile: ProfileItem,
) {
    init {
        require(port in 1024..65534) { "Invalid QUIC sidecar listen port" }
    }

    enum class Engine(val binaryName: String, val configName: String) {
        SING_BOX("libsingbox.so", "config.json"),
        SHADOW_QUIC("libshadowquic.so", "config.yaml"),
        NAIVE("libnaive.so", "config.json"),
    }

    /** The only loopback port the session reserves: the client's SOCKS5 listener. */
    val sessionPorts: List<Int> get() = listOf(port)

    /** Stable identity for matching a live process without logging a credential. */
    val identity: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(configText(null).toByteArray(Charsets.UTF_8))
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** What the editor reports about a profile; the order is the order the screen checks in. */
    enum class Problem { SERVER_MISSING, PORT_INVALID, CREDENTIALS_MISSING, KEYS_MISSING, ADDRESS_MISSING, PARAMS_INVALID }

    /** A log line of the client with its secrets taken out, in case an engine ever echoes them. */
    fun redact(text: String): String {
        var out = text
        secrets().forEach { secret -> if (secret.length >= 4) out = out.replace(secret, REDACTED) }
        return out
    }

    private fun secrets(): List<String> = buildList {
        profile.password?.takeIf { it.isNotBlank() }?.let(::add)
        profile.username?.takeIf { it.isNotBlank() }?.let(::add)
        profile.secretKey?.takeIf { it.isNotBlank() }?.let(::add)
        profile.preSharedKey?.takeIf { it.isNotBlank() }?.let(::add)
    }

    /** The full client configuration, with [protectPath] wired in when the VPN owns socket protection. */
    fun configText(protectPath: String?, naiveBridgePort: Int = port + 1): String = when (engine) {
        Engine.SING_BOX -> singBoxConfig(protectPath)
        Engine.SHADOW_QUIC -> shadowQuicConfig(protectPath)
        Engine.NAIVE -> naiveConfig(naiveBridgePort)
    }

    // ── sing-box (TUIC v5 / NaiveProxy) ──────────────────────────────────────────────────────────

    private fun singBoxConfig(protectPath: String?): String {
        val root = JsonObject()
        root.add("log", JsonObject().apply {
            addProperty("level", "warn")
            addProperty("timestamp", true)
        })
        root.add("inbounds", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "mixed")
                addProperty("tag", IN_TAG)
                addProperty("listen", AppConfig.LOOPBACK)
                addProperty("listen_port", port)
            })
        })
        root.add("outbounds", JsonArray().apply { add(tuicOutbound(protectPath)) })
        root.add("route", JsonObject().apply { addProperty("final", OUT_TAG) })
        return root.toString()
    }

    private fun tuicOutbound(protectPath: String?): JsonObject {
        val params = paramsObject(profile.tuicParameters)
        return JsonObject().apply {
            addProperty("type", "tuic")
            addProperty("tag", OUT_TAG)
            addProperty("server", profile.server.orEmpty())
            addProperty("server_port", profile.serverPort?.toIntOrNull() ?: AppConfig.DEFAULT_PORT)
            addProperty("uuid", profile.username.orEmpty())
            addProperty("password", profile.password.orEmpty())
            addProperty("congestion_control", params.string("congestion_control") ?: "bbr")
            addProperty("udp_relay_mode", params.string("udp_relay_mode") ?: "native")
            addProperty("zero_rtt_handshake", params.bool("zero_rtt_handshake") ?: true)
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", profile.sni?.takeIf { it.isNotBlank() } ?: profile.server.orEmpty())
                add("alpn", JsonArray().apply {
                    (profile.alpn?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("h3"))
                        .forEach { add(it) }
                })
                addProperty("insecure", profile.insecure == true)
            })
            protectPath?.let { addProperty("protect_path", it) }
        }
    }

    private fun naiveConfig(bridgePort: Int): String {
        val host = profile.sni?.takeIf { it.isNotBlank() } ?: profile.server.orEmpty()
        val proxyHost = if (':' in host) "[$host]" else host
        val user = java.net.URLEncoder.encode(profile.username.orEmpty(), "UTF-8").replace("+", "%20")
        val pass = java.net.URLEncoder.encode(profile.password.orEmpty(), "UTF-8").replace("+", "%20")
        return JsonObject().apply {
            addProperty("listen", "socks://127.0.0.1:$port")
            addProperty("proxy", "https://$user:$pass@$proxyHost:${profile.serverPort}")
            // TLS and HTTP/2 remain end-to-end in official Chromium NaiveProxy. Only its TCP
            // connection to the server goes through our protected opaque byte relay.
            addProperty("host-resolver-rules", "MAP $host 127.0.0.1:$bridgePort")
        }.toString()
    }

    // ── ShadowQUIC (gaming baseline) ────────────────────────────────────────────────────────────

    private fun shadowQuicConfig(protectPath: String?): String {
        val params = paramsObject(profile.shadowquicParameters)
        val server = profile.server.orEmpty()
        val serverPort = profile.serverPort?.toIntOrNull() ?: AppConfig.DEFAULT_PORT
        val alpn = profile.alpn?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("h3")
        val sb = StringBuilder()
        sb.append("inbounds:\n")
        sb.append("  - tag: ").append(IN_TAG).append('\n')
        sb.append("    type: socks\n")
        sb.append("    bind-addr: ").append(yamlQuote("${AppConfig.LOOPBACK}:$port")).append('\n')
        sb.append("outbounds:\n")
        sb.append("  - tag: ").append(OUT_TAG).append('\n')
        sb.append("    type: shadowquic\n")
        sb.append("    addr: ").append(yamlQuote("$server:$serverPort")).append('\n')
        sb.append("    username: ").append(yamlQuote(profile.username.orEmpty())).append('\n')
        sb.append("    password: ").append(yamlQuote(profile.password.orEmpty())).append('\n')
        sb.append("    server-name: ").append(yamlQuote(profile.sni?.takeIf { it.isNotBlank() } ?: server)).append('\n')
        sb.append("    alpn: [").append(alpn.joinToString(", ") { yamlQuote(it) }).append("]\n")
        sb.append("    congestion-control: ").append(params.string("congestion_control") ?: "bbr").append('\n')
        sb.append("    zero-rtt: ").append(params.bool("zero_rtt") ?: true).append('\n')
        sb.append("    over-stream: ").append(params.bool("over_stream") ?: false).append('\n')
        sb.append("    initial-mtu: ").append(params.int("initial_mtu") ?: 1300).append('\n')
        sb.append("    min-mtu: ").append(params.int("min_mtu") ?: 1200).append('\n')
        sb.append("    mtu-discovery: ").append(params.bool("mtu_discovery") ?: true).append('\n')
        sb.append("    blackhole-detection: ").append(params.bool("blackhole_detection") ?: false).append('\n')
        sb.append("    keep-alive-interval: ").append(params.int("keep_alive_interval") ?: 10000).append('\n')
        sb.append("    gso: ").append(params.bool("gso") ?: true).append('\n')
        protectPath?.let { sb.append("    protect-path: ").append(yamlQuote(it)).append('\n') }
        sb.append("router:\n")
        sb.append("  default-outbound: ").append(OUT_TAG).append('\n')
        sb.append("log-level: ").append(yamlQuote("warn")).append('\n')
        return sb.toString()
    }

    companion object {
        /** Above GUTS (18002) so a session of each could coexist; only one QUIC sidecar runs at a time. */
        const val DEFAULT_SOCKS_PORT = 18003
        private const val IN_TAG = "quic-in"
        private const val OUT_TAG = "quic-out"
        private const val REDACTED = "***"

        /** The engine a protocol rides, or null when the protocol is not a QUIC sidecar. */
        fun engineOf(type: EConfigType): Engine? = when (type) {
            EConfigType.TUIC -> Engine.SING_BOX
            EConfigType.NAIVE -> Engine.NAIVE
            EConfigType.SHADOWQUIC -> Engine.SHADOW_QUIC
            else -> null
        }

        fun isQuicType(type: EConfigType): Boolean = engineOf(type) != null

        /** @return null when [profile] can start a session, else the first problem found. */
        fun normalize(profile: ProfileItem): Problem? {
            val engine = engineOf(profile.configType) ?: return Problem.PARAMS_INVALID
            if (profile.server.isNullOrBlank()) return Problem.SERVER_MISSING
            val port = profile.serverPort?.toIntOrNull()
            if (port == null || port !in 1..65535) return Problem.PORT_INVALID
            if (engine == Engine.NAIVE) {
                if (profile.insecure == true) return Problem.PARAMS_INVALID
                val host = profile.sni?.takeIf { it.isNotBlank() } ?: profile.server.orEmpty()
                if (host.any { it.isWhitespace() || it in ",/\\@?#" }) return Problem.PARAMS_INVALID
            }
            when (profile.configType) {
                EConfigType.TUIC, EConfigType.SHADOWQUIC, EConfigType.NAIVE -> {
                    if (profile.username.isNullOrBlank() || profile.password.isNullOrBlank()) return Problem.CREDENTIALS_MISSING
                }
                else -> {}
            }
            // Reject malformed advanced-parameter JSON early rather than at core start.
            val raw = when (engine) {
                Engine.SHADOW_QUIC -> profile.shadowquicParameters
                Engine.SING_BOX -> profile.tuicParameters
                Engine.NAIVE -> null
            }
            if (!raw.isNullOrBlank() && runCatching { JsonParser.parseString(raw).asJsonObject }.isFailure) {
                return Problem.PARAMS_INVALID
            }
            return null
        }

        fun of(profile: ProfileItem, listenPort: Int = DEFAULT_SOCKS_PORT): QuicCore {
            require(normalize(profile) == null) { "Invalid QUIC sidecar profile" }
            return QuicCore(engineOf(profile.configType)!!, listenPort, profile.copy())
        }

        /** Local addresses as sing-box prefixes; a bare IPv4 gets /32 and a bare IPv6 gets /128. */
        internal fun addressesOf(text: String?): List<String> =
            text.orEmpty().split(',', ';', '\n', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { addr ->
                    if (addr.contains('/')) addr
                    else if (addr.contains(':')) "$addr/128"
                    else "$addr/32"
                }

        private fun paramsObject(raw: String?): JsonObject =
            if (raw.isNullOrBlank()) JsonObject()
            else runCatching { JsonParser.parseString(raw).asJsonObject }.getOrDefault(JsonObject())

        /** A double-quoted YAML scalar with the escapes YAML requires. */
        internal fun yamlQuote(value: String): String {
            val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t")
            return "\"$escaped\""
        }
    }
}

private fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

private fun JsonObject.bool(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
