package com.v2ray.ang.omni.lab

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.security.MessageDigest

/** A template keeps its credentials and transport; only the explicitly selected endpoint changes. */
data class OmniBuildOptions(
    val remarks: String? = null,
    val hostHeader: String? = null,
    val sni: String? = null,
    val keepTransportHost: Boolean = false,
)

data class OmniBuiltConfiguration(
    val target: OmniScanTarget,
    val profile: ProfileItem,
    val fingerprint: String,
)

object OmniEndpointConfigFactory {
    fun build(template: ProfileItem, target: OmniScanTarget, options: OmniBuildOptions = OmniBuildOptions()): ProfileItem {
        require(template.configType in setOf(EConfigType.VLESS, EConfigType.VMESS, EConfigType.TROJAN)) {
            "Scanner templates must be VLESS, VMess or Trojan"
        }
        val built = template.copy(
            server = target.host,
            serverPort = target.port.toString(),
            remarks = options.remarks?.takeIf { it.isNotBlank() } ?: "${template.remarks.ifBlank { "Omni" }} · ${target.endpointId}",
            // The server list prefers this cached line. Never leave the template's old endpoint beside the new one.
            description = null,
            addedTime = System.currentTimeMillis(),
        )
        if (!options.keepTransportHost && !options.hostHeader.isNullOrBlank()) {
            when (built.network?.lowercase()) {
                "ws", "httpupgrade", "xhttp" -> built.host = options.hostHeader
            }
        }
        if (!options.sni.isNullOrBlank()) built.sni = options.sni
        return built
    }

    fun buildBatch(
        template: ProfileItem,
        targets: Iterable<OmniScanTarget>,
        options: OmniBuildOptions = OmniBuildOptions(),
    ): List<OmniBuiltConfiguration> {
        val seen = HashSet<String>()
        return targets.mapNotNull { target ->
            if (!seen.add(target.endpointId)) return@mapNotNull null
            val profile = build(template, target, options)
            OmniBuiltConfiguration(target, profile, fingerprint(profile))
        }
    }

    fun fingerprint(profile: ProfileItem): String {
        val identity = profile.duplicateIdentity()
        val canonical = listOf(
            identity.configType.name,
            identity.server.orEmpty(),
            identity.serverPort.orEmpty(),
            identity.password.orEmpty(),
            identity.method.orEmpty(),
            identity.flow.orEmpty(),
            identity.network.orEmpty(),
            identity.host.orEmpty(),
            identity.path.orEmpty(),
            identity.security.orEmpty(),
            identity.sni.orEmpty(),
            identity.alpn.orEmpty(),
            identity.fingerPrint.orEmpty(),
            identity.publicKey.orEmpty(),
            identity.shortId.orEmpty(),
        ).joinToString("\u0001")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
