package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils

/** Redirect only the transport socket. Keep the original transport destination/implicit Host intact. */
internal object DialAddressOverride {
    fun attach(outbound: OutboundBean, profile: ProfileItem) {
        if (profile.configType !in setOf(EConfigType.VLESS, EConfigType.TROJAN)) return
        val ip = profile.dialAddress?.takeIf { Utils.isPureIpAddress(it) } ?: return
        val port = profile.serverPort?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return
        outbound.connectionIpRedirect = (if (ip.contains(':')) "[$ip]" else ip) + ":$port"
    }

    fun materialize(outbounds: ArrayList<OutboundBean>) {
        val tags = outbounds.map { it.tag }.toMutableSet()
        val helpers = ArrayList<OutboundBean>()
        for (outbound in outbounds) {
            val redirect = outbound.connectionIpRedirect ?: continue
            var tag = "${outbound.tag}-connection-ip"
            while (!tags.add(tag)) tag += "-ip"
            val sockopt = outbound.ensureSockopt()
            helpers.add(OutboundBean(tag = tag, protocol = "freedom", mux = null,
                settings = OutboundBean.OutSettingsBean(redirect = redirect),
                streamSettings = OutboundBean.StreamSettingsBean(network = null, sockopt = sockopt.copy())))
            sockopt.dialerProxy = tag
            outbound.connectionIpRedirect = null
        }
        outbounds.addAll(helpers)
    }
}
