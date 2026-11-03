package com.v2ray.ang.dto.entities

import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils

data class ProfileItem(
    val configVersion: Int = 4,
    val configType: EConfigType,
    var subscriptionId: String = "",
    var addedTime: Long = System.currentTimeMillis(),

    var remarks: String = "",
    var description: String? = null,
    var server: String? = null,
    var serverPort: String? = null,

    var password: String? = null,
    var method: String? = null,
    var flow: String? = null,
    var username: String? = null,

    var network: String? = null,
    var headerType: String? = null,
    var host: String? = null,
    var path: String? = null,
    var seed: String? = null,
    var kcpMtu: Int? = null,
    var kcpTti: Int? = null,

    var quicSecurity: String? = null,
    var quicKey: String? = null,
    var mode: String? = null,
    var serviceName: String? = null,
    var authority: String? = null,
    var xhttpMode: String? = null,
    var xhttpExtra: String? = null,
    var finalMask: String? = null,

    var security: String? = null,
    var sni: String? = null,
    var alpn: String? = null,
    var fingerPrint: String? = null,
    var cipherSuites: String? = null,
    var insecure: Boolean? = null,
    var echConfigList: String? = null,
    var echOutbound: String? = null,
    var verifyPeerCertByName: String? = null,
    var pinnedCA256: String? = null,

    var publicKey: String? = null,
    var shortId: String? = null,
    var spiderX: String? = null,
    var mldsa65Verify: String? = null,

    var secretKey: String? = null,
    var preSharedKey: String? = null,
    var localAddress: String? = null,
    var reserved: String? = null,
    var mtu: Int? = null,
    var remoteDNS: String? = null,

    var obfsPassword: String? = null,
    var portHopping: String? = null,
    var portHoppingInterval: String? = null,
    @Deprecated("Use pinnedCA256")
    var pinSHA256: String? = null,
    var bandwidthDown: String? = null,
    var bandwidthUp: String? = null,

    var policyGroupType: String? = null,
    var policyGroupSubscriptionId: String? = null,
    var policyGroupFilter: String? = null,
    var policyGroupTestOutbounds: Boolean? = null,
    var policyGroupFallbackTag: String? = null,
    var proxyChainProfiles: String? = null,

    var browserDialerMode: String? = null,

    var dialMode: String? = null,

    /**
     * Xray targetStrategy of the outbound built for this profile; null means the profile's default, see
     * CoreOutboundBuilder.defaultTargetStrategy.
     */
    var targetStrategy: String? = null,

    var aetherProtocol: String? = null,
    var aetherTransport: String? = null,

    /**
     * The server name the MASQUE handshakes put in their ClientHello, the core's --masque-sni; null means
     * AppConfig.AETHER_MASQUE_SNI. Only a MASQUE tunnel takes it.
     */
    var aetherMasqueSni: String? = null,
    var aetherScanMode: String? = null,
    var aetherObfuscation: String? = null,

    /**
     * The ClientHello of the core's TLS handshakes, an AetherFingerprint type: its cipher suites, groups, key shares,
     * signature algorithms and extensions; null means Chrome's. Only a MASQUE tunnel takes it.
     */
    var aetherFingerprint: String? = null,
    var aetherIpVersion: String? = null,
    var aetherWiwOuter: String? = null,
    var aetherWiwInner: String? = null,
    var aetherFragment: Boolean? = null,
    var aetherFragmentSize: String? = null,
    var aetherFragmentDelay: String? = null,

    /** Whether the MASQUE handshake hides its server name with Encrypted Client Hello; null means it does not. */
    var aetherEch: Boolean? = null,

    /** The resolver the core asks for the ECH key: udp://, tcp:// or https://; null means AppConfig.AETHER_ECH_DNS. */
    var aetherEchDns: String? = null,

    /** The domain whose HTTPS record gives the ECH key; null means AppConfig.AETHER_ECH_DOMAIN. */
    var aetherEchDomain: String? = null,

    /** The resolvers names are looked up with inside the tunnel, comma-separated; null means the core's own. */
    var aetherDns: String? = null,

    /** The exit rule the core holds the tunnel to: country codes to allow, or with a leading ! to refuse; null means any exit. */
    var aetherExitLoc: String? = null,

    /**
     * The name of the profile the core dials out through: its outbound is the exit-node, as a proxy chain's hop is.
     * Like the hops of a chain, it goes by its name, which a subscription's update keeps while it gives every profile
     * a new guid. Null means freedom, with the finalMask and the dialMode of this profile. No link carries it: the
     * name is that of a profile of this device.
     */
    var aetherExitNode: String? = null,

    /**
     * The loopback port the Aether core of this profile listened on, from before every core came to
     * listen on the Aether listen port of the settings. Nothing reads it any more; profiles that carry
     * it keep it as they were stored.
     */
    var aetherListenPort: String? = null,

    /** Where Psiphon stands in the tunnel, an AetherPsiphon type; null means it is not used. */
    var aetherPsiphon: String? = null,
    var aetherPsiphonMode: String? = null,
    var aetherPsiphonCdnIps: String? = null,
    var aetherPsiphonCdnSni: String? = null,
    /** Which of the CDN edge lists built into Psiphon the fronting scan tries, AetherPsiphonCdnSet types comma separated; null means all. */
    var aetherPsiphonCdnSets: String? = null,
    var aetherPsiphonRegion: String? = null,
    /** Whether Psiphon starts from the server list the app bundles; null means yes, false means it fetches a fresh list first. */
    var aetherPsiphonBundledList: Boolean? = null,

    /** Where Tor stands in the tunnel, an AetherTor type; null means it is not used. */
    var aetherTor: String? = null,

    /** When Tor turns to bridges, an AetherTorBridges type; null means when Tor is blocked. */
    var aetherTorBridges: String? = null,

    /** The profile's own bridge lines, one per line as torrc writes them, used when aetherTorBridges says so. */
    var aetherTorBridgeLines: String? = null,

    /** Where Tor's fetched bridges come from, an AetherTorRelays type; null means bridgedb and the public relays. */
    var aetherTorRelays: String? = null,

    /** The command line of this profile's core, written by hand in place of the one built from the settings; null follows the settings. */
    var aetherCommand: String? = null,

    /** Real DNS engine used with masterDnsConfig: official MasterDNS or CottenDNS. */
    var masterDnsEngine: String? = null,

    /** Official MasterDnsVPN/CottenDNS client JSON or TOML, kept for its own Go engine. */
    var masterDnsConfig: String? = null,

    /** Official client_resolvers.txt content; blank means the documented sample default. */
    var masterDnsResolvers: String? = null,

    /** DNS transport: doh, dot, udp, or tcp where the selected engine supports it. */
    var dnsttTransport: String? = null,

    /** DoH URL or comma-separated resolver endpoints; authority metadata is kept separately. */
    var dnsttResolver: String? = null,

    /** Optional comma-separated 0/1 flags aligned with dnsttResolver; never shown as endpoint text. */
    var dnsttResolverAuthorities: String? = null,

    /** Root DNS zone delegated to the DNSTT server. */
    var dnsttDomain: String? = null,

    /** Hex-encoded Noise public key of the DNSTT server; this is public, not a private key. */
    var dnsttPublicKey: String? = null,

    /** Optional official uTLS distribution string. */
    var dnsttUtls: String? = null,

    /** Engine selected for this profile: official DNSTT, compatibility DNSTT, NoizDNS, VayDNS, or Slipstream. */
    var dnsttEngine: String? = null,

    /** SlipNet/NoizDNS authoritative-resolver tuning, not a server-side flag. */
    var dnsttAuthoritative: Boolean? = null,

    /** Enable NoizDNS wire/cover-traffic mode instead of plain DNSTT compatibility mode. */
    var dnsttNoiz: Boolean? = null,

    /** NoizDNS variable-label and aggressive cover-traffic mode. */
    var dnsttStealth: Boolean? = null,

    /** Optional NoizDNS per-query payload cap; zero means the engine default. */
    var dnsttMaxPayload: Int? = null,

    /** VayDNS wire and session tuning from SlipNet v19/v20 records. */
    var vaydnsDnsttCompat: Boolean? = null,
    var vaydnsRecordType: String? = null,
    var vaydnsMaxQnameLen: Int? = null,
    var vaydnsRps: Double? = null,
    var vaydnsIdleTimeout: Int? = null,
    var vaydnsKeepAlive: Int? = null,
    var vaydnsUdpTimeout: Int? = null,
    var vaydnsMaxNumLabels: Int? = null,
    var vaydnsClientIdSize: Int? = null,

    /** Canonical Soar profile tuning JSON; interpreted by the real Soar client launcher. */
    var soarSettings: String? = null,

    /** Slipstream QUIC keepalive, congestion control, and segmentation offload. */
    var slipstreamKeepAlive: Int? = null,
    var slipstreamCongestionControl: String? = null,
    var slipstreamGsoEnabled: Boolean? = null,

    /** Multi-resolver distribution: fanout or roundrobin. */
    var dnsttResolverMode: String? = null,

    /** Number of resolvers used for each round-robin query. */
    var dnsttRrSpreadCount: Int? = null,

    /** Optional credentials for the remote SOCKS5 service behind the tunnel. */
    var dnsttSocksUsername: String? = null,
    var dnsttSocksPassword: String? = null,

    /** Whether the raw DNSTT tunnel terminates at an SSH server (SlipNet dnstt_ssh). */
    var dnsttSshEnabled: Boolean? = null,

    /** SlipNet's DNS-through-SSH preference, retained for lossless profile exchange. */
    var dnsttSshForwardDns: Boolean? = null,

    /** SSH account and authentication used after the DNSTT transport connects. */
    var dnsttSshUsername: String? = null,
    var dnsttSshPassword: String? = null,
    var dnsttSshHost: String? = null,
    var dnsttSshPort: Int? = null,
    var dnsttSshAuthType: String? = null,
    var dnsttSshPrivateKey: String? = null,
    var dnsttSshKeyPassphrase: String? = null,

    /** GUTS doors: Google Apps Script web apps, one per line or comma separated. */
    var gutsDoors: String? = null,

    /** The shared secret the door and the exit node check; kept out of the command line. */
    var gutsKey: String? = null,

    /** The Google edge the client pins, or "auto" to scan for the fastest one that fronts. */
    var gutsGoogleIp: String? = null,

    /** The SNI presented to that edge; the connection must look like a Google one. */
    var gutsSni: String? = null,
) {

    companion object {
        fun create(configType: EConfigType): ProfileItem =
            ProfileItem(configType = configType)

        /**
         * PattNG: the names of the members of a proxy chain, as [proxyChainProfilesOf] writes them into
         * [proxyChainProfiles], in their order: separated by commas, a comma or a backslash that is part of a name
         * written after a backslash. A chain written before, whose names had no backslash, reads as it did.
         */
        fun proxyChainMembersOf(text: String?): List<String> {
            if (text.isNullOrEmpty()) return emptyList()
            val members = mutableListOf<String>()
            val member = StringBuilder()
            var index = 0
            while (index < text.length) {
                val char = text[index]
                val next = text.getOrNull(index + 1)
                when {
                    char == '\\' && (next == ',' || next == '\\') -> {
                        member.append(next)
                        index++
                    }

                    char == ',' -> {
                        members += member.toString()
                        member.clear()
                    }

                    else -> member.append(char)
                }
                index++
            }
            members += member.toString()
            return members
        }

        /** PattNG: [members], the names of the members of a proxy chain in their order, as [proxyChainProfiles] holds them. */
        fun proxyChainProfilesOf(members: List<String>): String =
            members.joinToString(",") { it.replace("\\", "\\\\").replace(",", "\\,") }
    }

    fun getServerAddressAndPort(): String {
        if (server.isNullOrEmpty() && configType == EConfigType.CUSTOM) {
            return "${AppConfig.LOOPBACK}:${AppConfig.PORT_SOCKS}"
        }
        if (configType == EConfigType.MASTERDNS || configType == EConfigType.DNSTT || configType == EConfigType.GUTS) {
            return server.orEmpty()
        }
        return "${Utils.getIpv6Address(server)}:$serverPort"
    }

    /**
     * Dedicated identity for "remove duplicate configurations".
     *
     * Ignores metadata that does not affect connection:
     * - configVersion
     * - subscriptionId
     * - addedTime
     * - remarks
     * - description
     *
     * All other fields, including configType, are included in the comparison.
     *
     * Returns a copy; the caller must not modify it further.
     */
    fun duplicateIdentity(): ProfileItem =
        copy(
            configVersion = 0,
            subscriptionId = "",
            addedTime = 0L,
            remarks = "",
            description = null
        )
}
