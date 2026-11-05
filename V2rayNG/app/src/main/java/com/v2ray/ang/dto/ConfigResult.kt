package com.v2ray.ang.dto

import com.v2ray.ang.core.AetherCore
import com.v2ray.ang.core.DnsttCore
import com.v2ray.ang.core.GutsCore
import com.v2ray.ang.core.MasterDnsCore
import com.v2ray.ang.core.QuicCore

data class ConfigResult(
    var status: Boolean,
    var guid: String? = null,
    var content: String = "",
    var errorMessage: String = "",
    /** True when [errorMessage] is a localized resource string meant for the screen. */
    var localizedError: Boolean = false,
    /** The Aether core the configuration runs on, when it has an Aether outbound; the daemon starts it. */
    var aetherCore: AetherCore? = null,
    /** The official MasterDnsVPN process the loopback SOCKS outbound depends on. */
    var masterDnsCore: MasterDnsCore? = null,
    /** The official DNSTT process the loopback SOCKS outbound depends on. */
    var dnsttCore: DnsttCore? = null,
    /** The guts client process the loopback SOCKS outbound depends on. */
    var gutsCore: GutsCore? = null,
    /** The QUIC-family sidecar (sing-box for TUIC / NaiveProxy, shadowquic) the loopback SOCKS outbound depends on. */
    var quicCore: QuicCore? = null,
)
